package com.nora.rag.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagControllerTest {

    private final RagController controller = new RagController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
