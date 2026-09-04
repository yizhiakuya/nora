package com.nora.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of agent-service (port 8083).
 * LangChain4j agent orchestration (ReAct loop, tools, SSE streaming) lands in Phase 2.
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
