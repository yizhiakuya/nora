package com.nora.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.AppSettingStore;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

/**
 * 自定义环境变量(2026-09-19 真实落地):设置页「环境变量」的持久化后端。
 *
 * <p>此前只存浏览器 localStorage——换浏览器/清缓存即丢,且 run_command
 * 执行时根本读不到(设置页承诺"供自定义技能与自动任务引用"是空的)。
 * 现在存 {@code app_setting} KV 表(key=env-vars,跨浏览器一致),
 * {@code TerminalService.run_command} 执行时注入子进程环境。
 *
 * <p>载荷:{@code {"vars": [{"key":"GH_TOKEN","value":"...","secret":true,"note":"..."}]}}
 * 读取时 secret 变量值打码(与 MCP secrets 同口径)——前端编辑需要原值时
 * 走 PUT 全量替换(值不回传,secret 项由前端按"留空=不修改"处理)。
 */
@RestController
@RequestMapping("/api/chat/settings/env-vars")
public class EnvVarsController {

    static final String SETTING_KEY = "env-vars";

    private final AppSettingStore appSettingStore;

    public EnvVarsController(AppSettingStore appSettingStore) {
        this.appSettingStore = appSettingStore;
    }

    /** GET → 变量清单(secret 值打码,只回前 3+后 3)。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(maskedVars());
    }

    /**
     * PUT → 全量替换变量清单(前端为唯一编辑器,全量语义最直白)。
     * 校验:key 必须匹配 {@code [A-Z][A-Z0-9_]*};同名去重(后者覆盖)。
     */
    @PutMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<List<Map<String, Object>>> replace(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("vars");
        if (!(raw instanceof List<?> list)) {
            throw BusinessException.validation("ENV_VARS_INVALID", "vars 必须是数组",
                    "示例: {\"vars\": [{\"key\":\"GH_TOKEN\",\"value\":\"...\",\"secret\":true}]}");
        }
        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> v = (Map<String, Object>) m;
            String key = String.valueOf(v.getOrDefault("key", "")).trim().toUpperCase();
            if (!key.matches("[A-Z][A-Z0-9_]*")) {
                throw BusinessException.validation("ENV_VAR_KEY_INVALID", "变量名非法: " + key,
                        "变量名需为大写字母/数字/下划线,且以字母开头(如 GH_TOKEN)");
            }
            String value = String.valueOf(v.getOrDefault("value", ""));
            if (value.isBlank()) {
                throw new BusinessException(400, "变量 " + key + " 缺少值");
            }
            // 保留已有 secret 值:前端提交打码值时视为"不修改"(编辑弹窗不回显原文)
            if (value.indexOf('•') >= 0) {
                String existing = existingValue(key);
                if (existing != null) {
                    value = existing;
                } else {
                    throw BusinessException.validation("ENV_VAR_MASKED_VALUE", "变量 " + key + " 的值看起来是打码占位,但服务端无原值可保留",
                            "请重新输入完整值");
                }
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("key", key);
            entry.put("value", value);
            entry.put("secret", Boolean.TRUE.equals(v.get("secret")));
            Object note = v.get("note");
            if (note != null && !String.valueOf(note).isBlank()) {
                entry.put("note", String.valueOf(note));
            }
            byKey.put(key, entry);
        }
        appSettingStore.save(SETTING_KEY, Map.of("vars", List.copyOf(byKey.values())));
        return ApiResponse.ok(maskedVars());
    }

    /** 执行层读取:key → value(未打码;供 run_command 注入子进程环境)。 */
    public static Map<String, String> resolveForExecution(AppSettingStore store) {
        Map<String, Object> raw = store.raw(SETTING_KEY);
        if (raw == null || !(raw.get("vars") instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                String key = String.valueOf(m.get("key") == null ? "" : m.get("key"));
                String value = String.valueOf(m.get("value") == null ? "" : m.get("value"));
                if (!key.isBlank() && !value.isBlank()) {
                    out.put(key, value);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> maskedVars() {
        Map<String, Object> raw = appSettingStore.raw(SETTING_KEY);
        if (raw == null || !(raw.get("vars") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("key", m.get("key"));
            boolean secret = Boolean.TRUE.equals(m.get("secret"));
            String value = String.valueOf(m.get("value") == null ? "" : m.get("value"));
            entry.put("value", secret ? mask(value) : value);
            entry.put("secret", secret);
            if (m.get("note") != null) {
                entry.put("note", m.get("note"));
            }
            out.add(entry);
        }
        return out;
    }

    private String existingValue(String key) {
        Map<String, Object> raw = appSettingStore.raw(SETTING_KEY);
        if (raw == null || !(raw.get("vars") instanceof List<?> list)) {
            return null;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && key.equals(m.get("key"))) {
                return String.valueOf(m.get("value") == null ? "" : m.get("value"));
            }
        }
        return null;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 6) {
            return "••••••";
        }
        return value.substring(0, 3) + "••••••" + value.substring(value.length() - 3);
    }
}
