package com.nora.auth;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthFilter auth;

    public AuthController(AuthFilter auth) { this.auth = auth; }

    @GetMapping("/status")
    public Map<String, Boolean> status(HttpServletRequest request) {
        return Map.of("authRequired", auth.authRequired(), "authenticated", auth.authenticated(request));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        if (token != null && !token.isBlank() && auth.matches(token.trim())) {
            return ResponseEntity.ok(Map.of("code", 0, "data", true, "message", "ok"));
        }
        return ResponseEntity.status(401).body(Map.of("code", 401, "message", "令牌不正确",
                "category", "UNAUTHORIZED", "retryable", false, "hint", "请核对服务端 NORA_AUTH_TOKEN 配置"));
    }
}
