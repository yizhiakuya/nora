package com.nora.agent.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 运行时可变的应用设置,持久化在 {@code app_setting} KV 表。
 *
 * <p>模式:静态配置(env/yml)提供启动默认值;经本 store 保存的值覆盖它,
 * 后续读取立即生效——无需重启。消费方注册 {@link #onLoad(String, Function)}
 * 应用器,把存储的 JSON 转成运行时变更(如换出站代理)。应用器在启动时
 * (行加载完成后)与每次保存时执行。
 */
@Service
public class AppSettingStore implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppSettingStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Function<Map<String, Object>, Void>> appliers = new java.util.concurrent.ConcurrentHashMap<>();

    public AppSettingStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 注册某设置如何应用到运行时。返回 this 便于链式调用。 */
    public AppSettingStore onLoad(String key, Function<Map<String, Object>, Void> applier) {
        appliers.put(key, applier);
        return this;
    }

    /** 启动:加载持久化覆盖值并在对外服务前应用。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT key, value FROM app_setting",
                    (rs, i) -> Map.of(
                            "key", rs.getString("key"),
                            "value", rs.getString("value") == null ? "{}" : rs.getString("value")));
            for (Map<String, Object> row : rows) {
                apply(row.get("key").toString(), row.get("value").toString());
            }
            if (!rows.isEmpty()) {
                log.info("AppSettingStore: applied {} persisted setting(s): {}", rows.size(),
                        rows.stream().map(r -> r.get("key")).toList());
            }
        } catch (Exception e) {
            // 表不存在(迁移未跑)或 DB 不可用:用静态默认值启动
            log.warn("AppSettingStore: could not load persisted settings, using static defaults ({})",
                    e.getMessage());
        }
    }

    /** 保存载荷并立即应用到运行时。 */
    public void save(String key, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update("""
                    INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?::jsonb, now())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                    """, key, json);
            apply(key, json);
        } catch (Exception e) {
            throw new com.nora.common.exception.BusinessException(500, "保存设置失败: " + e.getMessage());
        }
    }

    /**
     * 直接从表读取某设置的载荷(无缓存)。
     * 键无行时返回 null;默认值由调用方决定。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> raw(String key) {
        try {
            return jdbcTemplate.query(
                    "SELECT value FROM app_setting WHERE key = ?",
                    (rs, i) -> {
                        try {
                            return (Map<String, Object>) objectMapper.readValue(rs.getString("value"), Map.class);
                        } catch (Exception e) {
                            return null;
                        }
                    },
                    key).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void apply(String key, String json) {
        Function<Map<String, Object>, Void> applier = appliers.get(key);
        if (applier == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            applier.apply(payload);
        } catch (Exception e) {
            log.warn("AppSettingStore: failed to apply setting '{}': {}", key, e.getMessage());
        }
    }
}
