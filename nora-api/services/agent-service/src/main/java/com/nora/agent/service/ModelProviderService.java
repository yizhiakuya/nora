package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * CRUD over {@code schema_agent.model_provider}. API keys never leave the
 * service unmasked: responses carry a masked preview, the raw key is only
 * used server-side for connectivity tests.
 */
@Service
public class ModelProviderService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelProviderService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Lists providers with masked keys (frontend ModelProvider[]; soft-deleted excluded). */
    public List<ProviderView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, protocol, endpoint, api_key, enabled, models, status, model_settings FROM model_provider WHERE deleted_at IS NULL ORDER BY id",
                (rs, rowNum) -> new ProviderView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("protocol"),
                        rs.getString("endpoint"),
                        mask(rs.getString("api_key")),
                        rs.getBoolean("enabled"),
                        Arrays.asList((String[]) rs.getArray("models").getArray()),
                        rs.getString("status"),
                        parseModelSettings(rs.getString("model_settings"))));
    }

    /** Parses the per-model settings JSONB column; corrupt JSON is treated as absent. */
    private ModelSettings parseModelSettings(String raw) {
        return parseModelSettingsStatic(raw);
    }

    /** Static variant used by the orchestration layer to read a provider's settings JSON. */
    static ModelSettings parseModelSettingsStatic(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ModelSettings(java.util.Map.of());
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(raw);
            java.util.Map<String, PerModelSettings> out = new java.util.LinkedHashMap<>();
            java.util.Iterator<String> it = root.fieldNames();
            while (it.hasNext()) {
                String model = it.next();
                JsonNode node = root.get(model);
                List<String> levels = new java.util.ArrayList<>();
                JsonNode levelsNode = node.get("reasoningLevels");
                if (levelsNode != null && levelsNode.isArray()) {
                    for (JsonNode level : levelsNode) {
                        if (level.isTextual()) levels.add(level.asText());
                    }
                }
                Long contextWindow = node.has("contextWindow") && node.get("contextWindow").canConvertToLong()
                        ? node.get("contextWindow").asLong() : null;
                String protocol = node.has("protocol") && node.get("protocol").isTextual()
                        ? node.get("protocol").asText() : null;
                out.put(model, new PerModelSettings(contextWindow, levels,
                        node.has("defaultReasoningLevel") ? node.get("defaultReasoningLevel").asText(null) : null,
                        protocol));
            }
            return new ModelSettings(out);
        } catch (Exception e) {
            return new ModelSettings(java.util.Map.of());
        }
    }

    /** Serializes per-model settings into the JSONB column value. */
    private String writeModelSettings(ModelSettings settings) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            for (var entry : settings.models().entrySet()) {
                ObjectNode node = root.putObject(entry.getKey());
                PerModelSettings value = entry.getValue();
                if (value.contextWindow() != null) node.put("contextWindow", value.contextWindow());
                if (value.reasoningLevels() != null && !value.reasoningLevels().isEmpty()) {
                    var arr = node.putArray("reasoningLevels");
                    value.reasoningLevels().forEach(arr::add);
                }
                if (value.defaultReasoningLevel() != null) node.put("defaultReasoningLevel", value.defaultReasoningLevel());
                // 每模型协议覆盖(openai/responses/...);null = 继承服务商协议
                if (value.protocol() != null && !value.protocol().isBlank()) node.put("protocol", value.protocol());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    /** Creates a provider; key is stored as given (encryption is a later step). */
    public ProviderView create(String name, String protocol, String endpoint,
                               String apiKey, List<String> models,
                               ModelSettings modelSettings) {
        return upsert(null, name, protocol, endpoint, apiKey, true, models, "untested", modelSettings);
    }

    /** Updates any subset of fields; null fields keep their stored value. */
    public ProviderView update(long id, String name, Boolean enabled, List<String> models,
                               ModelSettings modelSettings) {
        return update(id, name, null, null, null, enabled, models, modelSettings);
    }

    /** Full-subset update; null protocol/endpoint/apiKey keep their stored value. */
    public ProviderView update(long id, String name, String protocol, String endpoint, String apiKey,
                               Boolean enabled, List<String> models, ModelSettings modelSettings) {
        List<StoredProvider> existing = jdbcTemplate.query(
                "SELECT name, protocol, endpoint, api_key, enabled, models, status, model_settings FROM model_provider WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new StoredProvider(
                        rs.getString("name"), rs.getString("protocol"), rs.getString("endpoint"),
                        rs.getString("api_key"), rs.getBoolean("enabled"),
                        Arrays.asList((String[]) rs.getArray("models").getArray()), rs.getString("status"),
                        parseModelSettings(rs.getString("model_settings"))),
                id);
        if (existing.isEmpty()) {
            return null;
        }
        StoredProvider current = existing.get(0);
        boolean credentialsChanged = (endpoint != null && !endpoint.equals(current.endpoint()))
                || (apiKey != null && !apiKey.isBlank() && !apiKey.equals(current.apiKey()));
        return upsert(id,
                name != null ? name : current.name(),
                protocol != null ? protocol : current.protocol(),
                endpoint != null ? endpoint : current.endpoint(),
                apiKey != null && !apiKey.isBlank() ? apiKey : current.apiKey(),
                enabled != null ? enabled : current.enabled(),
                models != null ? models : current.models(),
                // 端点或密钥变更后旧的连通状态不再可信，重置为未测试
                credentialsChanged ? "untested" : current.status(),
                modelSettings != null ? modelSettings : current.modelSettings());
    }

    /** Soft-deletes by id (row kept; queries filter it out). */
    public boolean delete(long id) {
        return jdbcTemplate.update(
                "UPDATE model_provider SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /** Marks the connectivity status of a provider (live rows only). */
    public void markStatus(long id, String status) {
        jdbcTemplate.update("UPDATE model_provider SET status = ? WHERE id = ? AND deleted_at IS NULL", status, id);
    }

    /** Replaces the model list of a provider (auto-discovered from upstream; live rows only). */
    public void updateModels(long id, List<String> models) {
        jdbcTemplate.update("UPDATE model_provider SET models = ? WHERE id = ? AND deleted_at IS NULL",
                models.toArray(new String[0]), id);
    }

    /** Loads the raw endpoint+key pair for a provider (connectivity test / chat use; live rows only). */
    public StoredCredentials credentials(long id) {
        List<StoredCredentials> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key FROM model_provider WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new StoredCredentials(rs.getString("endpoint"), rs.getString("api_key")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Returns the first enabled live provider with a usable key for chat execution. */
    public ActiveProvider activeProvider() {
        List<ActiveProvider> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key, models, protocol, model_settings FROM model_provider WHERE enabled = true AND api_key IS NOT NULL AND trim(api_key) <> '' AND deleted_at IS NULL ORDER BY id",
                SETTINGS_ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Returns the first enabled live provider that actually serves the requested model. */
    public ActiveProvider activeProvider(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return activeProvider();
        }
        List<ActiveProvider> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key, models, protocol, model_settings FROM model_provider "
                        + "WHERE enabled = true AND api_key IS NOT NULL AND trim(api_key) <> '' "
                        + "AND ?::text = ANY(models) AND deleted_at IS NULL ORDER BY id",
                SETTINGS_ROW_MAPPER,
                requestedModel);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final org.springframework.jdbc.core.RowMapper<ActiveProvider> SETTINGS_ROW_MAPPER =
            (rs, rowNum) -> {
                String raw = rs.getString("model_settings");
                // 直接存原始 JSON,编排层按模型取;避免二次解析开销
                return new ActiveProvider(rs.getString("endpoint"), rs.getString("api_key"),
                        Arrays.asList((String[]) rs.getArray("models").getArray()), rs.getString("protocol"),
                        raw == null || raw.isBlank() ? "{}" : raw);
            };

    private ProviderView upsert(Long id, String name, String protocol, String endpoint,
                                String apiKey, Boolean enabled, List<String> models, String status,
                                ModelSettings modelSettings) {
        String settingsJson = writeModelSettings(modelSettings == null
                ? new ModelSettings(java.util.Map.of()) : modelSettings);
        if (id == null) {
            Long newId = jdbcTemplate.queryForObject(
                    "INSERT INTO model_provider (name, protocol, endpoint, api_key, enabled, models, status, model_settings) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
                    Long.class, name, protocol, endpoint, apiKey, enabled,
                    models == null ? null : models.toArray(new String[0]), status, settingsJson);
            id = newId;
        } else {
            jdbcTemplate.update(
                    "UPDATE model_provider SET name = ?, enabled = ?, models = ?, status = ?, "
                            + "model_settings = ?::jsonb WHERE id = ?",
                    name, enabled, models == null ? null : models.toArray(new String[0]), status,
                    settingsJson, id);
        }
        return new ProviderView(id, name, protocol, endpoint, mask(apiKey), enabled,
                models == null ? List.of() : models, status, modelSettings);
    }

    static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        if (key.length() <= 8) {
            return "••••••••";
        }
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }

    /** Provider row as consumed by the frontend (key masked). */
    public record ProviderView(
            long id,
            String name,
            String protocol,
            String endpoint,
            String masked,
            boolean enabled,
            List<String> models,
            String status,
            ModelSettings modelSettings
    ) {
    }

    /**
     * Per-model overrides: context window + reasoning level config.
     * Serialized flat ({@code {"<model>": {...}}}) via @JsonAnyGetter so the
     * JSONB column and the REST payload share one shape.
     */
    public static class ModelSettings {

        private final java.util.Map<String, PerModelSettings> models;

        public ModelSettings(java.util.Map<String, PerModelSettings> models) {
            this.models = models == null ? java.util.Map.of() : models;
        }

        public java.util.Map<String, PerModelSettings> models() {
            return models;
        }

        /** Returns settings for one model, or empty defaults. */
        public PerModelSettings forModel(String model) {
            PerModelSettings s = model == null ? null : models.get(model);
            return s != null ? s : new PerModelSettings(null, List.of(), null);
        }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, PerModelSettings> any() {
            return models;
        }

        /** Deserializes the flat {@code {model: settings}} shape. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static ModelSettings fromJson(java.util.Map<String, PerModelSettings> models) {
            return new ModelSettings(models);
        }
    }

    /**
     * One model's settings. {@code reasoningLevels} empty = no restriction;
     * {@code defaultReasoningLevel} null/"auto" = family default applies;
     * {@code protocol} null = inherit the provider-level protocol.
     */
    public record PerModelSettings(Long contextWindow, List<String> reasoningLevels,
                                   String defaultReasoningLevel, String protocol) {

        public PerModelSettings {
            // Jackson 对缺失字段给 null;规范化为空列表,避免调用方 NPE
            if (reasoningLevels == null) reasoningLevels = List.of();
        }

        /** Jackson-compatible constructor: protocol is optional in payloads. */
        public PerModelSettings(Long contextWindow, List<String> reasoningLevels,
                                String defaultReasoningLevel) {
            this(contextWindow, reasoningLevels, defaultReasoningLevel, null);
        }
    }

    record StoredProvider(String name, String protocol, String endpoint, String apiKey,
                          boolean enabled, List<String> models, String status,
                          ModelSettings modelSettings) {
    }

    /** Raw endpoint+key pair (never returned to clients). */
    public record StoredCredentials(String endpoint, String apiKey) {
    }

    public record ActiveProvider(String endpoint, String apiKey, List<String> models, String protocol,
                                 String modelSettingsJson) {
    }
}
