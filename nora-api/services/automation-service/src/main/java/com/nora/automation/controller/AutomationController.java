package com.nora.automation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
