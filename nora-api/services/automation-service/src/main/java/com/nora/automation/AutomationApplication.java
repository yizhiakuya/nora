package com.nora.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scans {@code com.nora} so the shared nora-common advice applies; enables
 * the scheduler that fires due daily/weekly rules.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.nora")
public class AutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutomationApplication.class, args);
    }
}
