package com.nora.automation.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutomationControllerTest {

    private final AutomationController controller = new AutomationController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
