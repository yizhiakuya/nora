package com.nora.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Scans {@code com.nora} so the shared nora-common advice
 * ({@code GlobalExceptionHandler} → ApiResponse envelope) applies here too.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nora")
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
