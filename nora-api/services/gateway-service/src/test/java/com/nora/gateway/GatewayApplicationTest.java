package com.nora.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GatewayApplicationTest {

    @Test
    void applicationClassIsAnnotated() {
        SpringBootApplication annotation = GatewayApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(annotation, "GatewayApplication should be annotated with @SpringBootApplication");
    }

    @Test
    void applicationClassHasMainMethod() throws Exception {
        assertNotNull(GatewayApplication.class.getDeclaredMethod("main", String[].class));
        assertEquals("com.nora.gateway", GatewayApplication.class.getPackageName());
    }
}
