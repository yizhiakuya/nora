package com.nora.datasource.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatasourceControllerTest {

    private final DatasourceController controller = new DatasourceController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
