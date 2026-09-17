package com.nora.agent.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code schema_agent.model_provider} 的 CRUD。API key 绝不脱敏离开本服务:
 * 响应携带脱敏预览,原文只在服务端连通性测试时使用。
 */
@Service
public class ModelProviderService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelProviderService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 列出带脱敏 key 的 provider(前端 ModelProvider[];排除软删)。 */
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

    /** 解析 per-model 设置的 JSONB 列;坏 JSON 视为不存在。 */
    private ModelSettings parseModelSettings(String raw) {
        return parseModelSettingsStatic(raw);
    }

    /** 静态变体,供编排层读取 provider 的设置 JSON。 */
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
                // vision: 显式 true/false 覆盖;缺失 = 按模型名自动判断(见 ChatOrchestrationService)
                Boolean vision = node.has("vision") && node.get("vision").isBoolean()
                        ? node.get("vision").asBoolean() : null;
                out.put(model, new PerModelSettings(contextWindow, levels,
                        node.has("defaultReasoningLevel") ? node.get("defaultReasoningLevel").asText(null) : null,
                        protocol, vision));
            }
            return new ModelSettings(out);
        } catch (Exception e) {
            return new ModelSettings(java.util.Map.of());
        }
    }

    /** 把 per-model 设置序列化为 JSONB 列值。 */
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
                // 识图能力开关:null = 按模型名自动判断,不落库(保持列干净)
                if (value.vision() != null) node.put("vision", value.vision());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    /** 创建 provider;key 按原样存储(加密是后续步骤)。 */
    public ProviderView create(String name, String protocol, String endpoint,
                               String apiKey, List<String> models,
                               ModelSettings modelSettings) {
        return upsert(null, name, protocol, endpoint, apiKey, true, models, "untested", modelSettings);
    }

    /** 更新任意字段子集;null 字段保持已存值。 */
    public ProviderView update(long id, String name, Boolean enabled, List<String> models,
                               ModelSettings modelSettings) {
        return update(id, name, null, null, null, enabled, models, modelSettings);
    }

    /** 全子集更新;null 的 protocol/endpoint/apiKey 保持已存值。 */
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

    /** 按 id 软删(行保留;查询过滤掉)。 */
    public boolean delete(long id) {
        return jdbcTemplate.update(
                "UPDATE model_provider SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /** 标记 provider 的连通状态(仅存活行)。 */
    public void markStatus(long id, String status) {
        jdbcTemplate.update("UPDATE model_provider SET status = ? WHERE id = ? AND deleted_at IS NULL", status, id);
    }

    /** 替换 provider 的模型列表(自上游自动发现;仅存活行)。 */
    public void updateModels(long id, List<String> models) {
        jdbcTemplate.update("UPDATE model_provider SET models = ? WHERE id = ? AND deleted_at IS NULL",
                models.toArray(new String[0]), id);
    }

    /** 加载 provider 的原始 endpoint+key(连通测试/对话用;仅存活行)。 */
    public StoredCredentials credentials(long id) {
        List<StoredCredentials> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key FROM model_provider WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new StoredCredentials(rs.getString("endpoint"), rs.getString("api_key")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 返回第一个可用的启用存活 provider(有可用 key,供对话执行)。 */
    public ActiveProvider activeProvider() {
        List<ActiveProvider> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key, models, protocol, model_settings FROM model_provider WHERE enabled = true AND api_key IS NOT NULL AND trim(api_key) <> '' AND deleted_at IS NULL ORDER BY id",
                SETTINGS_ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 返回第一个实际提供所请求模型的启用存活 provider。 */
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

    /**
     * Returns the enabled live provider for chat execution. 显式渠道 id 优先——
     * 同名模型可同时存在于多个渠道,前端把所选渠道随请求下发,这里按 id 精确定位;
     * 渠道已删除/禁用、或模型列表已不含该模型(测试连通时被上游列表覆盖)时,
     * 按模型名回落解析(旧行为,保证历史请求仍可用)。绝不静默改跑该渠道的
     * 首个模型——「选的模型与实际请求不一致」是已修过的坑。
     */
    public ActiveProvider activeProvider(Long providerId, String requestedModel) {
        if (providerId != null) {
            List<ActiveProvider> rows = jdbcTemplate.query(
                    "SELECT endpoint, api_key, models, protocol, model_settings FROM model_provider "
                            + "WHERE id = ? AND enabled = true AND api_key IS NOT NULL AND trim(api_key) <> '' AND deleted_at IS NULL",
                    SETTINGS_ROW_MAPPER, providerId);
            if (!rows.isEmpty()) {
                ActiveProvider provider = rows.get(0);
                if (requestedModel == null || requestedModel.isBlank()
                        || (provider.models() != null && provider.models().contains(requestedModel))) {
                    return provider;
                }
            }
        }
        return activeProvider(requestedModel);
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
            // protocol/endpoint/api_key 必须写:update() 已把 null 解析为现值,upsert
            // 收到的就是最终值。此前 UPDATE 漏掉这三列,编辑弹窗改端点/密钥/默认协议
            // 显示成功但实际未落库(响应回显新值、DB 还是旧值——"显示与实际不一致"的根因)。
            jdbcTemplate.update(
                    "UPDATE model_provider SET name = ?, protocol = ?, endpoint = ?, api_key = ?, "
                            + "enabled = ?, models = ?, status = ?, model_settings = ?::jsonb WHERE id = ?",
                    name, protocol, endpoint, apiKey, enabled,
                    models == null ? null : models.toArray(new String[0]), status,
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

    /** 前端消费的 provider 行(key 脱敏)。 */
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
     * Per-model 覆盖:上下文窗口 + 思考等级配置。
     * 经 @JsonAnyGetter 平铺序列化({@code {"<model>": {...}}}),
     * 让 JSONB 列与 REST 载荷共用一种形态。
     */
    public static class ModelSettings {

        private final java.util.Map<String, PerModelSettings> models;

        public ModelSettings(java.util.Map<String, PerModelSettings> models) {
            this.models = models == null ? java.util.Map.of() : models;
        }

        public java.util.Map<String, PerModelSettings> models() {
            return models;
        }

        /** 返回某模型的设置,或空默认值。 */
        public PerModelSettings forModel(String model) {
            PerModelSettings s = model == null ? null : models.get(model);
            return s != null ? s : new PerModelSettings(null, List.of(), null);
        }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, PerModelSettings> any() {
            return models;
        }

        /** 反序列化平铺的 {@code {model: settings}} 形态。 */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static ModelSettings fromJson(java.util.Map<String, PerModelSettings> models) {
            return new ModelSettings(models);
        }
    }

    /**
     * 单个模型的设置。{@code reasoningLevels} 空 = 不限制;
     * {@code defaultReasoningLevel} null/"auto" = 用家族默认;
     * {@code protocol} null = 继承 provider 级协议;
     * {@code vision} null = 从模型名自动检测,TRUE/FALSE = 强制。
     */
    public record PerModelSettings(Long contextWindow, List<String> reasoningLevels,
                                   String defaultReasoningLevel, String protocol,
                                   Boolean vision) {

        public PerModelSettings {
            // Jackson 对缺失字段给 null;规范化为空列表,避免调用方 NPE
            if (reasoningLevels == null) reasoningLevels = List.of();
        }

        /** Jackson 兼容构造:载荷中 protocol/vision 可选。 */
        public PerModelSettings(Long contextWindow, List<String> reasoningLevels,
                                String defaultReasoningLevel) {
            this(contextWindow, reasoningLevels, defaultReasoningLevel, null, null);
        }

        /** Jackson 兼容构造:载荷中 vision 可选。 */
        public PerModelSettings(Long contextWindow, List<String> reasoningLevels,
                                String defaultReasoningLevel, String protocol) {
            this(contextWindow, reasoningLevels, defaultReasoningLevel, protocol, null);
        }
    }

    record StoredProvider(String name, String protocol, String endpoint, String apiKey,
                          boolean enabled, List<String> models, String status,
                          ModelSettings modelSettings) {
    }

    /** 原始 endpoint+key 对(绝不返回给客户端)。 */
    public record StoredCredentials(String endpoint, String apiKey) {
    }

    public record ActiveProvider(String endpoint, String apiKey, List<String> models, String protocol,
                                 String modelSettingsJson) {
    }
}
