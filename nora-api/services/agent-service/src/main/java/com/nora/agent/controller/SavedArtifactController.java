package com.nora.agent.controller;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

/**
 * 对话保存成果登记(B1,2026-09-27,评审报告:成果入口和实际保存行为没有对齐)。
 *
 * <p>此前「保存为文件/保存到知识库」的已保存状态只存浏览器 localStorage——
 * 换浏览器丢失,也无法从资料页找到「这个文件来自哪次对话」。本端点把
 * 保存关系落到服务端(内容仍在工作区/知识库,不复制第三份):
 *
 * <ul>
 *   <li>{@code POST /api/saved-artifacts}:登记/更新一条(按 kind+path 幂等 upsert);</li>
 *   <li>{@code GET /api/saved-artifacts}:列表(资料页「对话保存」入口);</li>
 *   <li>{@code DELETE /api/saved-artifacts}:删除登记(文件被删时同步)。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/saved-artifacts")
public class SavedArtifactController {

    private final JdbcTemplate jdbcTemplate;

    public SavedArtifactController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记一次保存(kind+path 幂等:同回答重复保存覆盖同一路径,登记随之更新)。
     *
     * @param request kind/name/path + 来源 sessionId/messageKey
     * @return 落库的登记行
     */
    @PostMapping
    public ApiResponse<ArtifactView> register(@RequestBody RegisterRequest request) {
        if (request == null || request.kind() == null || request.kind().isBlank()
                || request.path() == null || request.path().isBlank()) {
            throw new BusinessException(400, "kind 与 path 必填(workspace_file 相对路径 / knowledge_doc 文档 id)");
        }
        if (!List.of("workspace_file", "knowledge_doc").contains(request.kind())) {
            throw new BusinessException(400, "kind 只允许 workspace_file / knowledge_doc,收到: " + request.kind());
        }
        String name = request.name() == null || request.name().isBlank()
                ? request.path() : request.name().trim();
        return ApiResponse.ok(jdbcTemplate.queryForObject(
                "INSERT INTO saved_artifact (kind, path, name, session_id, message_key) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (kind, path) DO UPDATE SET name = EXCLUDED.name, "
                        + "session_id = EXCLUDED.session_id, message_key = EXCLUDED.message_key, updated_at = now() "
                        + "RETURNING id, kind, path, name, session_id, message_key, created_at",
                (rs, i) -> mapRow(rs),
                request.kind(), request.path().trim(), name,
                blankToNull(request.sessionId()), blankToNull(request.messageKey())));
    }

    /** 保存成果列表(最新在前;资料页「已保存成果」入口)。 */
    @GetMapping
    public ApiResponse<List<ArtifactView>> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return ApiResponse.ok(jdbcTemplate.query(
                "SELECT id, kind, path, name, session_id, message_key, created_at "
                        + "FROM saved_artifact ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, i) -> mapRow(rs),
                bounded));
    }

    /**
     * 删除登记(文件被删除时同步;不删实际文件——那是文件中心的职责)。
     *
     * @return 删除的行数
     */
    @DeleteMapping
    public ApiResponse<Integer> remove(@RequestParam("id") long id) {
        return ApiResponse.ok(jdbcTemplate.update("DELETE FROM saved_artifact WHERE id = ?", id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 行映射:created_at 格式化为本地挂钟 "yyyy-MM-dd HH:mm"(与其它端点展示口径一致)。 */
    private static ArtifactView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        String createdAt = ts == null ? null
                : ts.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return new ArtifactView(
                rs.getLong("id"), rs.getString("kind"), rs.getString("path"), rs.getString("name"),
                rs.getString("session_id"), rs.getString("message_key"), createdAt);
    }

    /** 登记行视图(createdAt 为本地挂钟字符串 "yyyy-MM-dd HH:mm")。 */
    public record ArtifactView(long id, String kind, String path, String name,
                               String sessionId, String messageKey,
                               String createdAt) {
    }

    /** POST /api/saved-artifacts 请求体。 */
    public record RegisterRequest(String kind, String path, String name,
                                  String sessionId, String messageKey) {
    }
}
