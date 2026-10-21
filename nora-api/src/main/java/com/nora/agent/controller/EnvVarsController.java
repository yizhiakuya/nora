package com.nora.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.EnvVarsService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

@RestController
@RequestMapping("/api/chat/settings/env-vars")
public class EnvVarsController {
    private final EnvVarsService envVars;

    public EnvVarsController(EnvVarsService envVars) {
        this.envVars = envVars;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(envVars.list());
    }

    @PutMapping
    public ApiResponse<List<Map<String, Object>>> replace(@RequestBody Map<String, Object> body) {
        if (!(body.get("vars") instanceof List<?> values)) {
            throw BusinessException.validation("ENV_VARS_INVALID", "vars 必须是数组", null);
        }
        return ApiResponse.ok(envVars.replace(values));
    }

    @PutMapping("/{key}")
    public ApiResponse<Map<String, Object>> set(@PathVariable String key, @RequestBody Value value) {
        return ApiResponse.ok(envVars.set(key, value.value(), value.secret(), value.note()));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String key) {
        return ApiResponse.ok(envVars.delete(key));
    }

    public record Value(String value, Boolean secret, String note) {}
}
