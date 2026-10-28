package com.nora.agent.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.DefaultToolExecutor;

/** 只编排程序；每次子调用仍由 ToolStepEmitter 决定权限并保存真实结果。 */
@Service
public class PtcProgramService {
    private static final List<ToolSpecification> SPECIFICATIONS = java.util.Arrays.stream(PtcProgramService.class.getMethods())
            .filter(m -> m.isAnnotationPresent(Tool.class)).map(ToolSpecifications::toolSpecificationFrom).toList();
    private final ObjectMapper json;
    private final PtcRuntime runtime;
    private final ChatToolsSpec specs;
    private final ToolStepEmitter emitter;
    private final ChatToolExecutor executor;
    private final TurnCancellation cancellation;

    PtcProgramService(ObjectMapper json, PtcRuntime runtime, ChatToolsSpec specs, ToolStepEmitter emitter,
                      ChatToolExecutor executor, TurnCancellation cancellation) {
        this.json = json; this.runtime = runtime; this.specs = specs; this.emitter = emitter;
        this.executor = executor; this.cancellation = cancellation;
    }

    public boolean available() { return runtime.available(); }

    private record Context(String parent, int round, String session, PermissionMode permission, boolean unattended,
                           LoopDetector loop, com.nora.rag.service.RetrievalService.RetrievalScope scope,
                           ChatOrchestrationService.ChatEventConsumer events,
                           List<McpServerService.McpToolResult.ImageBlock> images,
                           java.util.concurrent.ConcurrentSkipListMap<Integer, ChatStepDto> children) {}

    static List<ToolSpecification> specifications() {
        return SPECIFICATIONS;
    }

    @Tool(name = "load_tools", value = "PTC 工具发现。names 为空数组时返回可用工具目录；传准确名称返回完整 JSON 参数声明。随后在 execute_program 中 await tools.call(name,args) 调用。加载不授予权限。")
    public String loadTools(@P("工具名称数组；空数组列出目录") List<String> names) throws Exception {
        if (names == null || names.size() > 32) throw new IllegalArgumentException("names 必须为最多 32 项的数组");
        var catalog = specs.build().stream().filter(s -> !Set.of("load_tools", "ask_user").contains(s.name())).toList();
        if (names.isEmpty()) return json.writeValueAsString(catalog.stream().map(s -> java.util.Map.of(
                "name", s.name(), "description", s.description().split("[。\\n]", 2)[0])).toList());
        var result = json.createArrayNode();
        for (String name : names.stream().distinct().toList()) {
            var spec = catalog.stream().filter(s -> s.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
            result.add(json.readTree(spec.toJson()));
        }
        return result.toString();
    }

    @Tool(name = "execute_program", value = "执行一次隔离 JavaScript 程序，用 await tools.call(name,args) 串联工具，Promise.all 并发读取，用 output(value) 返回精简结果。先用 load_tools 查参数。每次调用返回 {callId,status,content,truncated,artifacts}，content 为文本；仅在明确是 JSON 时自行解析。必须检查 status；unknown 时停止并核实，不重试写入。所有调用必须 await；权限仍逐项审批。程序无网络、无工作区、无凭据，不保留变量，不可调用 execute_program/load_tools/ask_user。最多64次工具、4路只读并发、代码/输出各64KiB、10分钟总时限。图片在程序结束时单独提供给模型。父结果中的 calls 是服务端真实记录，不能被 output 覆盖。")
    public String executeProgram(@P("JavaScript 程序，可顶层 await") String code, InvocationParameters parameters) throws Exception {
        Context context = (Context) parameters.get("nora.ptc");
        try {
        var result = runtime.execute(code, new PtcRuntime.Calls() {
            @Override public boolean parallel(String name, String args) {
                return !TerminalService.isCommandTool(name) && emitter.canRunInParallel(name, args, context.permission());
            }
            @Override public ToolStepEmitter.InvocationResult invoke(int index, String name, String args) {
                var events = new ChatOrchestrationService.ChatEventConsumer() {
                    @Override public void step(ChatStepDto step) {
                        context.events().step(step.childOf(context.parent(), index));
                        context.children().put(index, step);
                    }
                    @Override public void delta(String token) { context.events().delta(token); }
                    @Override public void sources(List<CitationDto> sources) { context.events().sources(sources); }
                    @Override public void approvalRequired(com.nora.agent.dto.ApprovalRequestDto request) { context.events().approvalRequired(request); }
                };
                if (cancellation.isCancelled(context.session()) || Thread.currentThread().isInterrupted()
                        || Set.of("execute_program", "load_tools", "ask_user").contains(name)
                        || specs.build().stream().noneMatch(s -> s.name().equals(name))) {
                    String error = "工具未执行：程序已取消或子调用不支持 " + name;
                    events.step(new ChatStepDto(context.parent() + "-child-" + index, "tool", "PTC 子调用", error, null,
                            "declined", name, null, new ChatStepDto.StepResult(null, "未执行", null, null, false, error), context.round()));
                    return new ToolStepEmitter.InvocationResult("declined", ChatToolExecutor.textOutcome("ERROR: " + error, "未执行"));
                }
                context.loop().countAttempt();
                ToolStepEmitter.InvocationResult outcome;
                try {
                    outcome = emitter.invokeTool(context.parent() + "-child-" + index, name,
                            executor.restrictRetrievalArgs(name, args, context.scope()), context.loop(), context.round(),
                            context.permission(), context.session(), context.unattended(), events);
                } catch (RuntimeException failure) {
                    String error = "工具终态未确认，请核实后再操作: " + Texts.abbreviate(failure.getMessage(), 1000);
                    events.step(new ChatStepDto(context.parent() + "-child-" + index, "tool", "PTC 子调用", error, null,
                            "unknown", name, null, new ChatStepDto.StepResult(null, "结果未知", null, null, false, error), context.round()));
                    outcome = new ToolStepEmitter.InvocationResult("unknown", new ChatToolExecutor.ToolOutcome("ERROR: " + error,
                            "结果未知", null, false, List.of(), true));
                }
                if (outcome.outcome().images() != null) context.images().addAll(outcome.outcome().images());
                return outcome;
            }
        });
        return json.writeValueAsString(result);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            var ledger = context.children().entrySet().stream().map(entry -> new PtcRuntime.CallResult(entry.getKey(),
                    entry.getValue().toolName(), entry.getValue().status(), entry.getValue().result() != null
                    && Boolean.TRUE.equals(entry.getValue().result().truncated()), entry.getValue().result() == null
                    || entry.getValue().result().files() == null ? List.of() : entry.getValue().result().files())).toList();
            return json.writeValueAsString(new PtcRuntime.Result(List.of(), ledger,
                    "程序未完成，请核对子调用记录，勿重试已执行的写操作: " + Texts.abbreviate(failure.getMessage(), 2000)));
        }
    }

    void emitCalls(List<ToolExecutionRequest> calls, LoopDetector loop, List<WireMessage> messages, int round,
                   PermissionMode permission, String session, boolean unattended, ResolvedLlm llm,
                   ChatOrchestrationService.ChatEventConsumer events,
                   com.nora.rag.service.RetrievalService.RetrievalScope scope) throws Exception {
        for (var call : calls) {
            if ("ask_user".equals(call.name())) {
                emitter.emitToolCalls(List.of(call), loop, messages, round, permission, session, unattended, llm, events, scope);
                continue;
            }
            String id = "s-ptc-" + java.util.UUID.randomUUID();
            long started = System.currentTimeMillis();
            var input = executor.redactValue(new ChatStepDto.StepInput(null, null, null, null, call.arguments()), ChatStepDto.StepInput.class);
            events.step(new ChatStepDto(id, "tool", "execute_program".equals(call.name()) ? "执行 PTC 程序" : "发现工具",
                    null, null, "running", call.name(), input, null, round));
            var images = new CopyOnWriteArrayList<McpServerService.McpToolResult.ImageBlock>();
            String content;
            String status = "completed";
            try {
                var method = java.util.Arrays.stream(getClass().getMethods())
                        .filter(m -> m.isAnnotationPresent(Tool.class) && m.getAnnotation(Tool.class).name().equals(call.name()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("PTC 顶层只能调用 load_tools、execute_program、ask_user"));
                var args = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
                String field = "execute_program".equals(call.name()) ? "code" : "names";
                if (args == null || !args.isObject() || args.size() != 1 || !args.hasNonNull(field)
                        || (field.equals("code") ? !args.get(field).isTextual() : !args.get(field).isArray()))
                    throw new IllegalArgumentException("参数必须且只能包含 " + field);
                if (field.equals("names")) for (var name : args.get(field)) if (!name.isTextual()) throw new IllegalArgumentException("工具名称必须是字符串");
                var context = new Context(id, round, session, permission, unattended, loop, scope, events, images, new java.util.concurrent.ConcurrentSkipListMap<>());
                content = (String) DefaultToolExecutor.builder().object(this).originalMethod(method).methodToInvoke(method)
                        .propagateToolExecutionExceptions(true).build().executeWithContext(call,
                                InvocationContext.builder().chatMemoryId(session)
                                        .invocationParameters(InvocationParameters.from("nora.ptc", context)).build()).result();
                if (field.equals("code")) {
                    var result = json.readTree(content);
                    if (result.hasNonNull("error")) status = "failed";
                    for (var child : result.path("calls")) if (!"completed".equals(child.path("status").asText())) status = "partial";
                    for (var child : result.path("calls")) if ("unknown".equals(child.path("status").asText())) status = "unknown";
                }
            } catch (Exception failure) {
                content = "ERROR: PTC 执行未完成: " + Texts.abbreviate(failure.getMessage(), 2000);
                status = "failed";
            }
            content = executor.redact(content);
            events.step(new ChatStepDto(id, "tool", "execute_program".equals(call.name()) ? "执行 PTC 程序" : "发现工具",
                    null, System.currentTimeMillis() - started, status, call.name(), input,
                    new ChatStepDto.StepResult(content, status.equals("completed") ? "已完成" : "执行未完整完成", null, null, false,
                            status.equals("completed") ? null : content), round));
            emitter.backfillToolMessage(messages, call.id(), call.name(), content, images, llm);
            if (cancellation.isCancelled(session) || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("PTC 已取消");
        }
    }
}
