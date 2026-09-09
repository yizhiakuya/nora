package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runtime-mutable app settings persisted in the {@code app_setting} KV table.
 *
 * <p>Pattern: static config (env/yml) provides boot defaults; anything saved
 * through this store overrides it for subsequent reads — without a restart.
 * Consumers register an {@link #onLoad(String, Function)} applier that
 * converts the stored JSON into a runtime change (e.g. swapping the outbound
 * proxy). Appliers run on startup (after rows are loaded) and on every save.
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

    /** Registers how a saved setting is applied to runtime. Returns this for chaining. */
    public AppSettingStore onLoad(String key, Function<Map<String, Object>, Void> applier) {
        appliers.put(key, applier);
        return this;
    }

    /** Boot: load persisted overrides and apply them before traffic is served. */
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
            // table missing (migration not yet run) or DB down: boot with static defaults
            log.warn("AppSettingStore: could not load persisted settings, using static defaults ({})",
                    e.getMessage());
        }
    }

    /** Saves the payload and applies it to runtime immediately. */
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
     * Reads one setting's payload straight from the table (no cache).
     * Returns null when the key has no row; callers decide the defaults.
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
