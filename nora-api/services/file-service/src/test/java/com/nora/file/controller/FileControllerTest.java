package com.nora.file.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileControllerTest {

    private final FileController controller = new FileController();

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }
}
