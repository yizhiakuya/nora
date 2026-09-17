package com.nora.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 扫描 {@code com.nora},让共享的 nora-common advice 生效;
 * 启用触发到点 daily/weekly 规则的调度器。
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.nora")
public class AutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutomationApplication.class, args);
    }
}
