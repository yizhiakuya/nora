package com.nora.agent.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.nora.common.exception.BusinessException;

@Service
public class SavedArtifactService {
    private final JdbcTemplate jdbcTemplate;

    public SavedArtifactService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ArtifactView register(String kind, String path, String name, String sessionId, String messageKey) {
        if (kind == null || !List.of("workspace_file", "knowledge_doc").contains(kind)
                || path == null || path.isBlank()) {
            throw new BusinessException(400, "kind 必须是 workspace_file / knowledge_doc，path 必填");
        }
        String displayName = name == null || name.isBlank() ? path : name.trim();
        return jdbcTemplate.queryForObject(
                "INSERT INTO saved_artifact (kind, path, name, session_id, message_key) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (kind, path) DO UPDATE SET name = EXCLUDED.name, "
                        + "session_id = EXCLUDED.session_id, message_key = EXCLUDED.message_key, updated_at = now() "
                        + "RETURNING id, kind, path, name, session_id, message_key, created_at",
                (rs, i) -> mapRow(rs), kind, path.trim(), displayName,
                blankToNull(sessionId), blankToNull(messageKey));
    }

    public List<ArtifactView> list(int limit) {
        return jdbcTemplate.query(
                "SELECT id, kind, path, name, session_id, message_key, created_at "
                        + "FROM saved_artifact ORDER BY updated_at DESC, id DESC LIMIT ?",
                (rs, i) -> mapRow(rs), Math.max(1, Math.min(limit, 200)));
    }

    public int remove(long id) {
        return jdbcTemplate.update("DELETE FROM saved_artifact WHERE id = ?", id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ArtifactView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp("created_at");
        return new ArtifactView(rs.getLong("id"), rs.getString("kind"), rs.getString("path"),
                rs.getString("name"), rs.getString("session_id"), rs.getString("message_key"),
                timestamp == null ? null : timestamp.toLocalDateTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    public record ArtifactView(long id, String kind, String path, String name,
                               String sessionId, String messageKey, String createdAt) { }
}
