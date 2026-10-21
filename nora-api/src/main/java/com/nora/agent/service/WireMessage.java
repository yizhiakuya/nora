package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/** 模型循环直接保留 SDK 消息及签名，只附加 Nora 的轮次索引。 */
record WireMessage(ChatMessage message, Integer roundIndex) {
    WireMessage(ChatMessage message) { this(message, null); }

    static WireMessage system(String content) { return new WireMessage(SystemMessage.from(content)); }
    static WireMessage user(String content) { return new WireMessage(UserMessage.from(content)); }
    static WireMessage assistant(String content) { return new WireMessage(AiMessage.from(content)); }
    static WireMessage tool(String id, String name, String content) {
        return new WireMessage(ToolExecutionResultMessage.from(id, name, content));
    }

    String role() {
        return switch (message) {
            case SystemMessage ignored -> "system";
            case UserMessage ignored -> "user";
            case AiMessage ignored -> "assistant";
            case ToolExecutionResultMessage ignored -> "tool";
            default -> throw new IllegalArgumentException("Unsupported message: " + message.type());
        };
    }

    List<Content> contents() {
        return switch (message) {
            case SystemMessage system -> List.of(TextContent.from(system.text()));
            case AiMessage ai -> List.of(TextContent.from(ai.text() == null ? "" : ai.text()));
            case UserMessage user -> user.contents();
            case ToolExecutionResultMessage tool -> tool.contents();
            default -> throw new IllegalArgumentException("Unsupported message: " + message.type());
        };
    }

    String text() {
        return contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).collect(Collectors.joining("\n"));
    }

    List<ToolExecutionRequest> toolCalls() { return message instanceof AiMessage ai ? ai.toolExecutionRequests() : List.of(); }
    int imageCount() { return (int) contents().stream().filter(ImageContent.class::isInstance).count(); }

    WireMessage withContents(List<Content> contents) {
        ChatMessage updated = switch (message) {
            case UserMessage user -> user.toBuilder().contents(contents).build();
            case ToolExecutionResultMessage tool -> tool.toBuilder().contents(contents).build();
            default -> throw new IllegalArgumentException("Message cannot contain attachments: " + role());
        };
        return new WireMessage(updated, roundIndex);
    }

    WireMessage withImages(List<McpServerService.McpToolResult.ImageBlock> images) {
        if (images.isEmpty()) return this;
        var parts = new ArrayList<>(contents());
        for (var image : images) parts.add(ImageContent.from(image.base64Data(), image.mimeType()));
        return withContents(parts);
    }
}
