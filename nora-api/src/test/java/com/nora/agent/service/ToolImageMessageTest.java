package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ToolImageMessageTest {
    @Test
    void prepareToolImagesAndParallelResults() throws Exception {
        var mapper = new ObjectMapper();
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        var calls = java.util.List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("first").name("read_photo").arguments("{}").build(),
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("second").name("read_photo").arguments("{}").build());
        var images = java.util.List.of(new McpServerService.McpToolResult.ImageBlock("image/jpeg", "aW1hZ2U="));
        var messages = java.util.List.of(WireMessage.user("查看真实图片"), new WireMessage(dev.langchain4j.data.message.AiMessage.from(calls)),
                WireMessage.tool("first", "read_photo", "第一张").withImages(images), WireMessage.tool("second", "read_photo", "第二张").withImages(images),
                WireMessage.assistant("已查看"), WireMessage.tool("plain", "read_photo", "普通文本"), WireMessage.tool("last", "read_photo", "最后一张").withImages(images));
        client.chatCompletionsRequest(client.chatRequest(messages, java.util.List.of()));
        client.chatCompletionsRequest(dev.langchain4j.model.chat.request.ChatRequest.builder().messages(java.util.List.of(dev.langchain4j.data.message.UserMessage.from("结束"))).build());
    }
}
