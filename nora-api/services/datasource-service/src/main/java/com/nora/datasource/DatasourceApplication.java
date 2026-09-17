package com.nora.datasource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 扫描 {@code com.nora},让共享的 nora-common advice
 * ({@code GlobalExceptionHandler} → ApiResponse 信封)在这里同样生效。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nora")
public class DatasourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatasourceApplication.class, args);
    }
}
