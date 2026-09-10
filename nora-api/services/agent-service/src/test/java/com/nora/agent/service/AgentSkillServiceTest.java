package com.nora.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSkillServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AgentSkillService service;

    @BeforeEach
    void setUp() {
        service = new AgentSkillService(jdbcTemplate);
    }

    @Test
    void catalogBlockNullWhenNoEnabledSkills() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        assertNull(service.catalogBlock());
    }

    @Test
    void catalogBlockListsNameCategoryAndTruncatedDescription() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(3L);
        when(rs.getString("name")).thenReturn("周报生成");
        when(rs.getString("description")).thenReturn("x".repeat(200));
        when(rs.getString("instructions")).thenReturn("正文……");
        when(rs.getString("category")).thenReturn("计算");
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getObject("created_at", LocalDateTime.class)).thenReturn(LocalDateTime.now());
        when(rs.getObject("updated_at", LocalDateTime.class)).thenReturn(LocalDateTime.now());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<AgentSkillService.SkillView> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        String catalog = service.catalogBlock();

        assertTrue(catalog.contains("manage_skill"), "目录引导用 manage_skill read 读全文");
        assertTrue(catalog.contains("- 周报生成 [计算]: "));
        assertTrue(catalog.contains("…"), "超长描述被截断");
        assertTrue(!catalog.contains("x".repeat(90)), "正文不进目录");
    }

    @Test
    void createRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("  ", "desc", "instr", "自定义"));
    }

    @Test
    void createRejectsOverlongName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("n".repeat(AgentSkillService.MAX_NAME_CHARS + 1), null, null, null));
    }

    @Test
    void updateWithNullFieldsKeepsExistingValuesAndFlipsEnabled() {
        AgentSkillService.SkillView existing = new AgentSkillService.SkillView(
                9L, "旧名", "旧描述", "旧正文", "自定义", true, null, null);
        // get(id) 用 ResultSetExtractor 重载:lenient 返回 existing 视图
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.query(anyString(),
                        org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<AgentSkillService.SkillView>>any(),
                        any()))
                .thenReturn(existing);

        service.update(9L, null, null, null, null, false);

        // null 字段保持旧值,只有 enabled 翻转为 false
        org.mockito.Mockito.verify(jdbcTemplate).update(anyString(),
                eq("旧名"), eq("旧描述"), eq("旧正文"), eq("自定义"), eq(false), eq(9L));
    }
}
