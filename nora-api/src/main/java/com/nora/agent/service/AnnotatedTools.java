package com.nora.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;

/** 方法注解同时提供模型契约和 SDK 执行入口；审批与运行生命周期由 Nora 管理。 */
@Component
class AnnotatedTools {
    static final Set<String> CORE_TOOLS = Set.of("manage_workspace", "manage_file", "open_file",
            TerminalService.toolName(), "command_execution", "search_knowledge", "manage_skill", "ask_user", "load_tools");
    private final ObjectMapper mapper;
    private final ObjectMapper argumentsMapper;
    private final Map<String, Binding> tools = new LinkedHashMap<>();

    private record Binding(AiServiceTool tool, java.lang.reflect.Method method, Class<?> inputType, boolean descriptionField) { }

    AnnotatedTools(ObjectMapper mapper, SqlTools sql,
                   BuiltinTools builtin, FileWorkspaceTools files, McpManagementTools mcp, QuestionTools questions) {
        this.mapper = mapper;
        this.argumentsMapper = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            argumentsMapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                    .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
        register(SqlTools.class, sql);
        register(BuiltinTools.class, builtin);
        register(FileWorkspaceTools.class, files);
        register(McpManagementTools.class, mcp);
        register(QuestionTools.class, questions);
        register(AnnotatedTools.class, this);
    }

    private void register(Class<?> type, Object bean) {
        for (var method : type.getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) continue;
            var specification = ToolSpecifications.toolSpecificationFrom(method);
            Class<?> inputType = null;
            if (method.getParameterCount() > 0 && method.getParameterTypes()[0].isRecord()) {
                inputType = method.getParameterTypes()[0];
                var inputSchema = (JsonObjectSchema) specification.parameters().properties().get("input");
                specification = specification.toBuilder().parameters(inputSchema).build();
            }
            if ("terminal".equals(specification.name())) {
                specification = specification.toBuilder().name(TerminalService.toolName()).build();
            }
            if ("load_tools".equals(specification.name())) {
                String catalog = tools.values().stream().map(binding -> binding.tool().toolSpecification())
                        .filter(spec -> !CORE_TOOLS.contains(spec.name()))
                        .sorted(java.util.Comparator.comparing(ToolSpecification::name))
                        .map(spec -> spec.name() + "：" + spec.description().split("[。\\n]", 2)[0])
                        .collect(java.util.stream.Collectors.joining("\n"));
                specification = specification.toBuilder().description(specification.description() + "\n可加载目录：\n" + catalog).build();
            }
            var parameters = specification.parameters() == null ? JsonObjectSchema.builder().build() : specification.parameters();
            if (!parameters.properties().containsKey("description")) {
                parameters = parameters.toBuilder().addProperty("description", JsonStringSchema.builder()
                        .description("展示给用户的一句话步骤标题，不含密码或密钥").build()).build();
            }
            specification = specification.toBuilder().parameters(parameters.toBuilder().additionalProperties(false).build()).build();
            var executor = DefaultToolExecutor.builder().object(bean)
                    .originalMethod(method).methodToInvoke(method)
                    .wrapToolArgumentsExceptions(true).propagateToolExecutionExceptions(true).build();
            var tool = AiServiceTool.builder().toolSpecification(specification).toolExecutor(executor).build();
            if (tools.putIfAbsent(tool.name(), new Binding(tool, method, inputType, inputType != null && hasDescriptionField(inputType))) != null) {
                throw new IllegalStateException("Duplicate annotated tool: " + tool.name());
            }
        }
    }

    List<ToolSpecification> specifications() {
        return tools.values().stream().map(binding -> binding.tool().toolSpecification()).toList();
    }

    boolean contains(String name) {
        return tools.containsKey(name);
    }

    @Tool(name = "load_tools", value = "按需加载专用工具的完整参数声明。names 使用下方目录中的准确名称，可一次加载多个；下一轮按新声明直接调用，不要猜参数。只加载本任务需要的能力；已在当前工具列表中的无需重复加载。此操作不执行目标工具，也不改变权限。MCP 外部工具通过 manage_mcp 发现。")
    public String loadTools(@dev.langchain4j.agent.tool.P(name = "input") ToolInputs.LoadTools input) {
        var names = input.names();
        if (names == null || names.isEmpty()) return "ERROR: names 至少指定一个工具名称";
        for (String name : names) {
            if (name == null || !tools.containsKey(name)) return "ERROR: 工具不存在: " + name + "，请使用目录中的准确名称";
        }
        return mapper.createObjectNode().set("loadedTools", mapper.valueToTree(names.stream().distinct().toList())).toString();
    }

    ChatToolExecutor.ToolOutcome execute(String name, String arguments, ToolCallContext runtime) {
        Object result;
        try {
            var binding = tools.get(name);
            JsonNode input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(arguments == null ? "{}" : arguments);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("参数必须是 JSON 对象");
            var schema = binding.tool().toolSpecification().parameters();
            for (String required : schema.required()) {
                if (!input.hasNonNull(required)) throw new IllegalArgumentException("缺少参数 " + required);
            }
            var fields = input.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!schema.properties().containsKey(field)) throw new IllegalArgumentException("未知参数 " + field);
            }
            if (binding.inputType() != null) {
                // Jackson 在边界拒绝布尔值、整数等参数的隐式强转；SDK 按方法签名执行。
                ObjectNode data = (ObjectNode) input.deepCopy();
                if (!binding.descriptionField()) data.remove("description");
                argumentsMapper.treeToValue(data, binding.inputType());
                input = mapper.createObjectNode().set("input", data);
            } else {
                for (var parameter : binding.method().getParameters()) {
                    var annotation = parameter.getAnnotation(dev.langchain4j.agent.tool.P.class);
                    String field = annotation == null || annotation.name().isEmpty() ? parameter.getName() : annotation.name();
                    if (input.hasNonNull(field)) argumentsMapper.treeToValue(input.get(field), parameter.getType());
                }
            }
            var request = ToolExecutionRequest.builder().name(name)
                    .arguments(input.toString()).build();
            var context = InvocationContext.builder().chatMemoryId(runtime.sessionId())
                    .invocationParameters(InvocationParameters.from("nora.tool", runtime)).build();
            result = binding.tool().toolExecutor().executeWithContext(request, context).result();
        } catch (ToolArgumentsException | JsonProcessingException e) {
            return ChatToolExecutor.textOutcome("ERROR: 工具参数的 JSON 结构或类型不符合声明", null);
        } catch (IllegalArgumentException e) {
            return ChatToolExecutor.textOutcome("ERROR: 工具参数无效: " + e.getMessage(), null);
        } catch (ToolExecutionException e) {
            // SDK 默认会将取消与异常转成文本，Nora 必须继续处理原始终态。
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
        return switch (result) {
            case SqlTools.SqlOutcome sql -> new ChatToolExecutor.ToolOutcome(sql.content(), sql.summary(),
                    null, sql.truncated(), List.of(), sql.outcomeUnknown());
            case ChatToolExecutor.ToolOutcome outcome -> outcome;
            case String text -> ChatToolExecutor.textOutcome(text, null);
            default -> throw new IllegalStateException("Unsupported result for tool: " + name);
        };
    }

    private static boolean hasDescriptionField(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents()).anyMatch(component -> "description".equals(component.getName()));
    }
}
