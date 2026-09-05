package com.nora.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Scans {@code com.nora} so the shared nora-common advice applies.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nora")
public class EnvApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnvApplication.class, args);
    }
}
