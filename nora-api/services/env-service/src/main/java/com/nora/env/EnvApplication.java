package com.nora.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scans {@code com.nora} so the shared nora-common advice applies.
 * EnableScheduling:PROC 源进程守护的 10s 调和扫描。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nora")
@EnableScheduling
public class EnvApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnvApplication.class, args);
    }
}
