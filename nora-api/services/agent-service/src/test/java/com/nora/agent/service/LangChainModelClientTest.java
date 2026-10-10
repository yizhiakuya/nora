package com.nora.agent.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;

class LangChainModelClientTest {
    @Test
    void messagesToolsAndImages() throws Exception {
        var mapper = new ObjectMapper();
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        var body = (ObjectNode) mapper.readTree("""
                {"messages":[
                  {"role":"system","content":"系统要求"},
                  {"role":"user","content":"查看图片"},
                  {"role":"assistant","content":"读取图片","tool_calls":[
                    {"id":"first","type":"function","function":{"name":"photo","arguments":"{}"}},
                    {"id":"second","type":"function","function":{"name":"photo","arguments":"{}"}}
                  ]},
                  {"role":"tool","tool_call_id":"first","content":[
                    {"type":"text","text":"图片1"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,aW1hZ2U="}}
                  ]},
                  {"role":"tool","tool_call_id":"second","content":[
                    {"type":"image_url","image_url":{"url":"https://example.org/image.jpg"}}
                  ]}
                ],"tools":[{"type":"function","function":{"name":"photo","description":"照片",
                  "parameters":{"$schema":"https://json-schema.org/draft/2020-12/schema",
                    "title":"PhotoArguments","type":"object","additionalProperties":false,
                    "properties":{"id":{"type":"string","minLength":1}},"required":["id"]}}}],
                "tool_choice":"none"}
                """);
        client.chatRequest(body);
        client.chatRequest(client.chatCompletionsBody(body));
        var ai = AiMessage.builder().text("继续查看").thinking("检查图片")
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("third").name("photo").arguments("{}").build()))
                .attributes(Map.of("thinking_signature", "signed")).build();
        body.withArray("messages").addObject().put("role", "assistant").put("content", "继续查看")
                .putPOJO("_langchain4j_ai", ai);
        client.chatRequest(body);
        ((ObjectNode) body.withArray("messages").get(2)).put("phase", "commentary");
        for (String protocol : List.of("openai", "responses", "anthropic")) {
            var llm = new ResolvedLlm("http://localhost/v1", "key", "deepseek-v4.1-flash", protocol, "max", null, null);
            client.baseBody(true, llm);
            client.prepareRequest(llm, body, HttpRequest.builder().url("http://localhost/v1")
                    .method(HttpMethod.POST).body("{\"max_tokens\":1024,\"input\":[{\"role\":\"assistant\",\"content\":\"读取图片\"}]}").build());
        }
    }
}
