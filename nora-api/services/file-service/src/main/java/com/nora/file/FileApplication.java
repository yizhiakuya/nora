package com.nora.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point of file-service (port 8081): file upload, Tika-based text
 * extraction, preview, and fire-and-forget indexing via rag-service.
 * Scans the whole {@code com.nora} tree so nora-common's
 * {@code GlobalExceptionHandler} is registered alongside this service's beans.
 */
@SpringBootApplication(scanBasePackages = "com.nora")
@EnableAsync
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
