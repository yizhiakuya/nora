package com.nora.agent.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.agent.tool.ToolSpecification;

/** 内置 SDK 声明与远端 MCP 声明仅在工具版本变化时合并。 */
@Component
class ChatToolsSpec {
    private final ObjectMapper mapper;
    private final AnnotatedTools annotated;
    private final McpServerService mcp;
    private final TerminalService terminal;
    private long revision = Long.MIN_VALUE;
    private List<ToolSpecification> specifications;

    ChatToolsSpec(ObjectMapper mapper, AnnotatedTools annotated, McpServerService mcp, TerminalService terminal) {
        this.mapper = mapper; this.annotated = annotated; this.mcp = mcp; this.terminal = terminal;
    }

    long toolsRevision() { return mcp == null ? 0 : mcp.toolsRevision(); }
    String terminalRuntimeContext() { return terminal == null ? "" : terminal.runtimeContext(); }

    synchronized List<ToolSpecification> build() {
        long current = toolsRevision();
        if (specifications != null && revision == current) return specifications;
        var tools = new ArrayList<>(annotated.specifications());
        if (mcp != null) for (var mounted : mcp.mountedTools()) {
            ObjectNode function = mapper.createObjectNode().put("name", mounted.mountedName())
                    .put("description", mounted.description() == null || mounted.description().isBlank()
                        ? "MCP 工具(" + mounted.serverName() + " 提供)" : mounted.description());
            ObjectNode schema = mounted.inputSchema() instanceof ObjectNode node ? node.deepCopy()
                    : mapper.createObjectNode().put("type", "object");
            schema.remove(List.of("$schema", "title"));
            function.set("parameters", schema);
            tools.add(ToolSpecification.fromJson(function.toString()));
        }
        dev.langchain4j.agent.tool.ToolSpecifications.validateSpecifications(tools);
        specifications = List.copyOf(tools); revision = current;
        return specifications;
    }

    List<ToolSpecification> forMessages(List<WireMessage> messages) {
        var selected = new HashSet<>(AnnotatedTools.CORE_TOOLS);
        for (var message : messages) {
            for (var call : message.toolCalls()) selected.add(call.name());
            if (message.message() instanceof dev.langchain4j.data.message.ToolExecutionResultMessage result
                    && "load_tools".equals(result.toolName()) && message.text().startsWith("{")) {
                try {
                    var loaded = mapper.readTree(message.text()).path("loadedTools");
                    if (loaded.isArray()) for (var name : loaded) if (name.isTextual()) selected.add(name.asText());
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // 历史结果可能已被压缩；没有有效回执时由模型按目录重新加载。
                }
            }
        }
        return build().stream().filter(spec -> selected.contains(spec.name()) || spec.name().startsWith("mcp__")).toList();
    }

}
