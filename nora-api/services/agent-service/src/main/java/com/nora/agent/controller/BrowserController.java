package com.nora.agent.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.nora.agent.service.BrowserService;
import com.nora.common.response.ApiResponse;

@RestController
@RequestMapping("/api/workspace/browser/{sessionId}")
public class BrowserController {
    private final BrowserService browser;
    public BrowserController(BrowserService browser) { this.browser = browser; }

    @GetMapping
    public ApiResponse<JsonNode> state(@PathVariable String sessionId, @RequestParam(defaultValue = "false") boolean history) {
        return ApiResponse.ok(browser.state(sessionId, history));
    }

    @PostMapping
    public ApiResponse<JsonNode> control(@PathVariable String sessionId, @RequestBody JsonNode args) { return ApiResponse.ok(browser.control(sessionId, args)); }

    @PostMapping("/tasks")
    public ApiResponse<JsonNode> task(@PathVariable String sessionId, @RequestBody JsonNode args) { return ApiResponse.ok(browser.task(sessionId, args)); }

    @GetMapping("/frame")
    public ResponseEntity<byte[]> frame(@PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(browser.frame(sessionId));
    }
}
