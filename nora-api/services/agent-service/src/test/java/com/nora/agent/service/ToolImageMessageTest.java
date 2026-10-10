package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ToolImageMessageTest {
    @Test
    void prepareToolImagesAndParallelResults() throws Exception {
        var mapper = new ObjectMapper();
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        var body = (ObjectNode) mapper.readTree("""
                {"model":"vision","messages":[
                  {"role":"user","content":"查看真实图片"},
                  {"role":"assistant","tool_calls":[
                    {"id":"first","type":"function","function":{"name":"read_photo","arguments":"{}"}},
                    {"id":"second","type":"function","function":{"name":"read_photo","arguments":"{}"}}
                  ]},
                  {"role":"tool","tool_call_id":"first","content":[
                    {"type":"text","text":"第一张"},
                    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,aW1hZ2U="}}
                  ]},
                  {"role":"tool","tool_call_id":"second","content":[
                    {"type":"text","text":"第二张"},
                    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,aW1hZ2U="}}
                  ]},
                  {"role":"assistant","content":"已查看"},
                  {"role":"tool","tool_call_id":"plain","content":"普通文本"},
                  {"role":"tool","tool_call_id":"last","content":[
                    {"type":"text","text":"最后一張"},
                    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,aW1hZ2U="}}
                  ]}
                ]}
                """);
        mapper.writeValueAsString(client.chatCompletionsBody(body));
        mapper.writeValueAsString(client.chatCompletionsBody(mapper.createObjectNode()));
    }
}
