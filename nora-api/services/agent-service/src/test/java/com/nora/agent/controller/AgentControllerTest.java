package com.nora.agent.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit test: instantiates the controller directly, no Spring context, no Nacos.
 */
class AgentControllerTest {

    private final AgentController controller = new AgentController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
