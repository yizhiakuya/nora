package com.nora.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 扫描 {@code com.nora},让共享的 nora-common advice 生效。
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
