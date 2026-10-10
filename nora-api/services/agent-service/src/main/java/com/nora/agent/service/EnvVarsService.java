package com.nora.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;

/** 设置页和 Agent 共用的环境变量存储；明文只交给执行层。 */
@Service
public class EnvVarsService {
    private static final String SETTING_KEY = "env-vars";
    private final AppSettingStore store;
    private volatile List<String> secrets = List.of();

    public EnvVarsService(AppSettingStore store) {
        this.store = store;
        store.onLoad(SETTING_KEY, payload -> {
            rememberSecrets(entries(payload).values().stream().filter(v -> Boolean.TRUE.equals(v.get("secret")))
                    .map(v -> (String) v.get("value")).toList());
            return null;
        });
    }

    List<String> secrets() { return secrets; }

    synchronized void rememberSecrets(java.util.Collection<String> values) {
        // 旧连接和后台进程仍可能输出旧值，轮换后也继续遮蔽。
        secrets = java.util.stream.Stream.concat(secrets.stream(), values.stream()).filter(v -> v != null && !v.isEmpty())
                .distinct().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String redact(String text) { return redact(text, secrets); }

    static String redact(String text, List<String> secrets) {
        if (text == null) return null;
        for (String secret : secrets) if (!secret.isEmpty()) text = text.replace(secret, "[REDACTED]");
        return ChatToolExecutor.redactToolContent(text);
    }

    public List<Map<String, Object>> list() {
        return entries(store.raw(SETTING_KEY)).values().stream().map(EnvVarsService::masked).toList();
    }

    public Map<String, Object> set(String key, String value, Boolean secret, String note) {
        String name = normalizeKey(key);
        validateValue(value);
        Map<String, Object> saved = store.update(SETTING_KEY, payload -> {
            var vars = entries(payload);
            Map<String, Object> previous = vars.get(name);
            String keptNote = note != null ? note : previous == null ? null : (String) previous.get("note");
            vars.put(name, entry(name, value, secret == null || secret, keptNote));
            return Map.of("vars", List.copyOf(vars.values()));
        });
        return masked(entries(saved).get(name));
    }

    public Map<String, Object> delete(String key) {
        String name = normalizeKey(key);
        boolean[] deleted = {false};
        store.update(SETTING_KEY, payload -> {
            var vars = entries(payload);
            deleted[0] = vars.remove(name) != null;
            return Map.of("vars", List.copyOf(vars.values()));
        });
        return Map.of("key", name, "deleted", deleted[0]);
    }

    /** 既有 API 的全量替换；遮蔽值保留原值，校验失败则整笔回滚。 */
    public List<Map<String, Object>> replace(List<?> values) {
        Map<String, Object> saved = store.update(SETTING_KEY, payload -> {
            var existing = entries(payload);
            Map<String, Map<String, Object>> next = new LinkedHashMap<>();
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> v) || !(v.get("value") instanceof String value)) {
                    throw BusinessException.validation("ENV_VARS_INVALID", "变量项必须包含字符串 value", null);
                }
                String name = normalizeKey(v.get("key") instanceof String s ? s : null);
                if (value.indexOf('•') >= 0 && existing.containsKey(name)) {
                    value = (String) existing.get(name).get("value");
                }
                validateValue(value);
                if (v.get("secret") != null && !(v.get("secret") instanceof Boolean)) {
                    throw BusinessException.validation("ENV_VARS_INVALID", "secret 必须是布尔值", null);
                }
                next.put(name, entry(name, value, Boolean.TRUE.equals(v.get("secret")),
                        v.get("note") == null ? null : v.get("note").toString()));
            }
            return Map.of("vars", List.copyOf(next.values()));
        });
        return entries(saved).values().stream().map(EnvVarsService::masked).toList();
    }

    public Map<String, String> resolveForExecution() {
        Map<String, String> values = new LinkedHashMap<>();
        entries(store.raw(SETTING_KEY)).forEach((key, entry) -> values.put(key, (String) entry.get("value")));
        return values;
    }

    private static String normalizeKey(String key) {
        String name = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z][A-Z0-9_]*")) {
            throw BusinessException.validation("ENV_VAR_KEY_INVALID", "变量名需以字母开头，只包含字母、数字、下划线", null);
        }
        return name;
    }

    private static void validateValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw BusinessException.validation("ENV_VAR_VALUE_INVALID", "变量值不能为空或包含 NUL 字符", null);
        }
        if (value.indexOf('•') >= 0 || value.equals("***") || value.equals("[REDACTED]")) {
            throw BusinessException.validation("ENV_VAR_MASKED_VALUE", "不能将脱敏占位符保存为变量值", "请提供完整值；已存密钥直接通过 ${KEY} 引用");
        }
    }

    private static Map<String, Object> entry(String key, String value, boolean secret, String note) {
        Map<String, Object> entry = new LinkedHashMap<>(Map.of("key", key, "value", value, "secret", secret));
        if (note != null && !note.isBlank()) entry.put("note", note);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> entries(Map<String, Object> payload) {
        Map<String, Map<String, Object>> vars = new LinkedHashMap<>();
        if (payload == null || !payload.containsKey("vars")) return vars;
        if (!(payload.get("vars") instanceof List<?> list)) {
            throw new BusinessException(500, "环境变量存储格式错误");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> v) || !(v.get("key") instanceof String key)
                    || !(v.get("value") instanceof String)) {
                throw new BusinessException(500, "环境变量存储格式错误");
            }
            vars.put(key, new LinkedHashMap<>((Map<String, Object>) v));
        }
        return vars;
    }

    private static Map<String, Object> masked(Map<String, Object> stored) {
        Map<String, Object> result = entry((String) stored.get("key"), (String) stored.get("value"),
                Boolean.TRUE.equals(stored.get("secret")), stored.get("note") instanceof String note ? note : null);
        if (Boolean.TRUE.equals(stored.get("secret"))) {
            result.put("value", "••••••");
            if (stored.get("note") instanceof String note && !((String) stored.get("value")).isEmpty()) {
                result.put("note", note.replace((String) stored.get("value"), "••••••"));
            }
        }
        return result;
    }
}
