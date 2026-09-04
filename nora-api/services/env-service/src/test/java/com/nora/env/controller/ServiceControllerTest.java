package com.nora.env.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceControllerTest {

    private final ServiceController controller = new ServiceController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
