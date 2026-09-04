package com.nora.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Placeholder controller. The real SSE chat endpoint (/api/chat streaming) is deferred to Phase 2.
 */
@RestController
@RequestMapping("/api/chat")
public class AgentController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
