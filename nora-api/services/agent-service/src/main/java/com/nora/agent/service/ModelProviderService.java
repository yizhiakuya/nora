package com.nora.agent.service;

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

    public ModelProviderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Lists providers with masked keys (frontend ModelProvider[]). */
    public List<ProviderView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, protocol, endpoint, api_key, enabled, models, status FROM model_provider ORDER BY id",
                (rs, rowNum) -> new ProviderView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("protocol"),
                        rs.getString("endpoint"),
                        mask(rs.getString("api_key")),
                        rs.getBoolean("enabled"),
                        Arrays.asList((String[]) rs.getArray("models").getArray()),
                        rs.getString("status")));
    }

    /** Creates a provider; key is stored as given (encryption is a later step). */
    public ProviderView create(String name, String protocol, String endpoint,
                               String apiKey, List<String> models) {
        return upsert(null, name, protocol, endpoint, apiKey, true, models, "untested");
    }

    /** Updates any subset of fields; null fields keep their stored value. */
    public ProviderView update(long id, String name, Boolean enabled, List<String> models) {
        List<StoredProvider> existing = jdbcTemplate.query(
                "SELECT name, protocol, endpoint, api_key, enabled, models, status FROM model_provider WHERE id = ?",
                (rs, rowNum) -> new StoredProvider(
                        rs.getString("name"), rs.getString("protocol"), rs.getString("endpoint"),
                        rs.getString("api_key"), rs.getBoolean("enabled"),
                        Arrays.asList((String[]) rs.getArray("models").getArray()), rs.getString("status")),
                id);
        if (existing.isEmpty()) {
            return null;
        }
        StoredProvider current = existing.get(0);
        return upsert(id,
                name != null ? name : current.name(),
                current.protocol(),
                current.endpoint(),
                current.apiKey(),
                enabled != null ? enabled : current.enabled(),
                models != null ? models : current.models(),
                current.status());
    }

    /** Deletes by id. */
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", id) > 0;
    }

    /** Marks the connectivity status of a provider. */
    public void markStatus(long id, String status) {
        jdbcTemplate.update("UPDATE model_provider SET status = ? WHERE id = ?", status, id);
    }

    /** Replaces the model list of a provider (auto-discovered from upstream). */
    public void updateModels(long id, List<String> models) {
        jdbcTemplate.update("UPDATE model_provider SET models = ? WHERE id = ?",
                models.toArray(new String[0]), id);
    }

    /** Loads the raw endpoint+key pair for a provider (connectivity test / chat use). */
    public StoredCredentials credentials(long id) {
        List<StoredCredentials> rows = jdbcTemplate.query(
                "SELECT endpoint, api_key FROM model_provider WHERE id = ?",
                (rs, rowNum) -> new StoredCredentials(rs.getString("endpoint"), rs.getString("api_key")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }    private ProviderView upsert(Long id, String name, String protocol, String endpoint,
                                String apiKey, Boolean enabled, List<String> models, String status) {
        if (id == null) {
            Long newId = jdbcTemplate.queryForObject(
                    "INSERT INTO model_provider (name, protocol, endpoint, api_key, enabled, models, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class, name, protocol, endpoint, apiKey, enabled,
                    models == null ? null : models.toArray(new String[0]), status);
            id = newId;
        } else {
            jdbcTemplate.update(
                    "UPDATE model_provider SET name = ?, enabled = ?, models = ?, status = ? WHERE id = ?",
                    name, enabled, models == null ? null : models.toArray(new String[0]), status, id);
        }
        return new ProviderView(id, name, protocol, endpoint, mask(apiKey), enabled,
                models == null ? List.of() : models, status);
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
            String status
    ) {
    }

    record StoredProvider(String name, String protocol, String endpoint, String apiKey,
                          boolean enabled, List<String> models, String status) {
    }

    /** Raw endpoint+key pair (never returned to clients). */
    public record StoredCredentials(String endpoint, String apiKey) {
    }
}
