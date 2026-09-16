package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Wire-format message wrapper (JsonNode so tool messages mix in). */
    record WireMessage(ObjectNode node) {

        static WireMessage system(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "system");
            n.put("content", content);
            return new WireMessage(n);
        }

        static WireMessage user(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "user");
            n.put("content", content);
            return new WireMessage(n);
        }

        static WireMessage assistant(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "assistant");
            n.put("content", content);
            return new WireMessage(n);
        }
    }
