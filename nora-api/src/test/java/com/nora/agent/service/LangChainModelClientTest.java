package com.nora.agent.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;

class LangChainModelClientTest {
    @Test
    void messagesToolsAndImages() throws Exception {
        var mapper = new ObjectMapper();
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        var calls = List.of(ToolExecutionRequest.builder().id("first").name("photo").arguments("{}").build(),
                ToolExecutionRequest.builder().id("second").name("photo").arguments("{}").build());
        var ai = AiMessage.builder().text("继续查看").thinking("检查图片")
                .toolExecutionRequests(calls).attributes(Map.of("thinking_signature", "signed", "phase", "commentary")).build();
        var messages = List.of(WireMessage.system("系统要求"), WireMessage.user("查看图片"), new WireMessage(ai),
                WireMessage.tool("first", "photo", "图片1").withImages(List.of(new McpServerService.McpToolResult.ImageBlock("image/png", "aW1hZ2U="))),
                WireMessage.tool("second", "photo", "图片2").withContents(List.of(dev.langchain4j.data.message.ImageContent.from("https://example.org/image.jpg"))));
        var body = client.chatRequest(messages, ToolSmokeFixtures.annotated(mapper).specifications());
        client.chatCompletionsRequest(body);
        for (String protocol : List.of("openai", "responses", "anthropic")) {
            var llm = new ResolvedLlm("http://localhost/v1", "key", "deepseek-v4.1-flash", protocol, "max", null, null);
            client.reasoningOptions(llm);
            client.prepareRequest(llm, body, HttpRequest.builder().url("http://localhost/v1")
                    .method(HttpMethod.POST).body("{\"max_tokens\":1024,\"input\":[{\"role\":\"assistant\",\"content\":\"读取图片\"}]}").build());
        }
    }
}
