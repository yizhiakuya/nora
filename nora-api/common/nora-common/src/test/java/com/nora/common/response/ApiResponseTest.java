package com.nora.common.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTest {

    @Test
    void okCarriesDataWithSuccessCodeAndMessage() {
        ApiResponse<String> response = ApiResponse.ok("nora");

        assertEquals(0, response.code());
        assertEquals("nora", response.data());
        assertEquals("ok", response.message());
    }

    @Test
    void errorCarriesCodeAndMessageWithoutData() {
        ApiResponse<String> response = ApiResponse.error(1001, "file not found");

        assertEquals(1001, response.code());
        assertNull(response.data());
        assertEquals("file not found", response.message());
    }
}
