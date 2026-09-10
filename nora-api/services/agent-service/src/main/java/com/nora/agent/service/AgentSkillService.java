package com.nora.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 指令型技能:名称 + 描述 + 指令正文,启用后以「技能目录」注入系统提示,
 * agent 用 {@code manage_skill} 工具(或 read 动作)按需读取全文遵循。
 *
 * <p>与 MCP 工具的分工:MCP = 可执行的外部工具(有 schema、真调用);
 * 技能 = 指令文本(指导 agent 如何用现有工具完成一类任务),更接近
 * Claude Code 的 skill 概念。渐进披露:目录常驻、正文按需读,避免
 * 全量注入挤占上下文预算。
 */
@Service
public class AgentSkillService {

    /** 技能正文长度上限(字符)。 */
    static final int MAX_INSTRUCTIONS_CHARS = 20_000;
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_DESC_CHARS = 500;
    /** 目录注入:最多列出条数(每行截断到 80 字符)。 */
    static final int MAX_CATALOG_ENTRIES = 60;
    private static final int CATALOG_DESC_CHARS = 80;

    private final JdbcTemplate jdbcTemplate;

    public AgentSkillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SkillView(long id, String name, String description, String instructions,
                            String category, boolean enabled,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        /** 列表视图:不携带正文(避免列表接口传输大文本)。 */
        public SkillView summary() {
            return new SkillView(id, name, description, null, category, enabled, createdAt, updatedAt);
        }
    }

    public List<SkillView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, description, instructions, category, enabled, created_at, updated_at FROM agent_skill ORDER BY updated_at DESC, id DESC",
                (rs, i) -> map(rs));
    }

    public SkillView get(long id) {
        return jdbcTemplate.query(
                "SELECT id, name, description, instructions, category, enabled, created_at, updated_at FROM agent_skill WHERE id = ?",
                rs -> rs.next() ? map(rs) : null,
                id);
    }

    public SkillView getByName(String name) {
        return jdbcTemplate.query(
                "SELECT id, name, description, instructions, category, enabled, created_at, updated_at FROM agent_skill WHERE lower(name) = lower(?)",
                rs -> rs.next() ? map(rs) : null,
                name);
    }

    public SkillView create(String name, String description, String instructions, String category) {
        String n = normalizeName(name);
        jdbcTemplate.update(
                "INSERT INTO agent_skill (name, description, instructions, category) VALUES (?, ?, ?, ?)",
                n,
                truncate(description, MAX_DESC_CHARS),
                truncate(instructions, MAX_INSTRUCTIONS_CHARS),
                category == null || category.isBlank() ? "自定义" : truncate(category.trim(), 50));
        return getByName(n);
    }

    /** 更新(name 冲突时抛错;null 字段保持原值)。 */
    public SkillView update(long id, String name, String description, String instructions,
                            String category, Boolean enabled) {
        SkillView existing = get(id);
        if (existing == null) {
            throw new IllegalArgumentException("技能不存在: " + id);
        }
        String nextName = name == null || name.isBlank() ? existing.name() : normalizeName(name);
        String nextDesc = description == null ? existing.description() : truncate(description, MAX_DESC_CHARS);
        String nextInstr = instructions == null ? existing.instructions() : truncate(instructions, MAX_INSTRUCTIONS_CHARS);
        String nextCategory = category == null || category.isBlank() ? existing.category() : truncate(category.trim(), 50);
        boolean nextEnabled = enabled == null ? existing.enabled() : enabled;
        jdbcTemplate.update(
                "UPDATE agent_skill SET name = ?, description = ?, instructions = ?, category = ?, enabled = ?, updated_at = now() WHERE id = ?",
                nextName, nextDesc, nextInstr, nextCategory, nextEnabled, id);
        return get(id);
    }

    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM agent_skill WHERE id = ?", id) > 0;
    }

    /**
     * 启用技能的目录(名称+描述)注入文本;无启用技能返回 null。
     * 正文不在此处展开——agent 需要时用 manage_skill action=read 拉取。
     */
    public String catalogBlock() {
        List<SkillView> enabled = jdbcTemplate.query(
                "SELECT id, name, description, instructions, category, enabled, created_at, updated_at FROM agent_skill WHERE enabled = TRUE ORDER BY updated_at DESC, id DESC",
                (rs, i) -> map(rs));
        if (enabled.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(
                "以下是用户启用的技能(Skill)目录。当任务与某个技能相关时,先用 manage_skill 工具 action=read 读取它的完整指令并严格遵循:\n");
        int count = 0;
        for (SkillView s : enabled) {
            if (count >= MAX_CATALOG_ENTRIES) {
                break;
            }
            String desc = s.description() == null || s.description().isBlank() ? "(无描述)" : s.description();
            if (desc.length() > CATALOG_DESC_CHARS) {
                desc = desc.substring(0, CATALOG_DESC_CHARS) + "…";
            }
            sb.append("- ").append(s.name()).append(" [").append(s.category()).append("]: ").append(desc).append('\n');
            count++;
        }
        return sb.toString();
    }

    private static SkillView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SkillView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("instructions"),
                rs.getString("category"),
                rs.getBoolean("enabled"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("技能名称过长(上限 " + MAX_NAME_CHARS + " 字符)");
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…(已截断)";
    }
}
