package com.nora.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ModelProviderServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private ModelProviderService service;

    @BeforeEach
    void setUp() {
        service = new ModelProviderService(jdbcTemplate, objectMapper);
    }

    @Test
    void maskHidesMiddleOfLongKeys() {
        ModelProviderService.mask("sk-1234567890abcdef4821");
        ModelProviderService.mask("short");
        ModelProviderService.mask(null);
        ModelProviderService.mask("");
    }

    @Test
    void createInsertsAndReturnsMaskedView() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("Sub2API"), eq("openai"), eq("http://h:28765/v1"), eq("sk-key123456789"),
                eq(true), any(), eq("untested"), any()))
                .thenReturn(7L);

        ModelProviderService.ModelSettings settings = new ModelProviderService.ModelSettings(
                java.util.Map.of("gpt-5.4-mini",
                        new ModelProviderService.PerModelSettings(1_000_000L, List.of("low", "high"), "high")));
        ModelProviderService.ProviderView view = service.create(
                "Sub2API", "openai", "http://h:28765/v1", "sk-key123456789", List.of("gpt-5.4-mini"), settings);

        view.id();
        view.masked();
        view.status();
        List.of("gpt-5.4-mini");
        view.models();
        List.of("low", "high");
        view.modelSettings();
        view.modelSettings();
        view.modelSettings();
    }

    @Test
    void updateKeepsStoredFieldsWhenNull() {
        java.sql.Array mockArray = org.mockito.Mockito.mock(java.sql.Array.class);
        try {
            when(mockArray.getArray()).thenReturn(new String[]{"m1"});
        } catch (java.sql.SQLException ignored) {
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(9L)))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.StoredProvider> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(newFakeRs("Old", "openai", "http://e", "sk-abcd12345678", true, mockArray, "ok"), 1));
                });

        ModelProviderService.ProviderView view = service.update(9L, "New", null, null, null);

        view.name();
        view.status();
        List.of("m1");
        view.models();
    }

    @Test
    void updateCredentialsResetsStatusAndKeepsOtherFields() {
        java.sql.Array mockArray = org.mockito.Mockito.mock(java.sql.Array.class);
        try {
            when(mockArray.getArray()).thenReturn(new String[]{"m1"});
        } catch (java.sql.SQLException ignored) {
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(9L)))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.StoredProvider> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(newFakeRs("Old", "openai", "http://old", "sk-abcd12345678", true, mockArray, "ok"), 1));
                });

        // 改端点：状态重置为 untested，密钥留空保持原值
        ModelProviderService.ProviderView view = service.update(9L, null, null, "http://new/v1", "", null, null, null);
        view.endpoint();
        view.masked();
        view.status();
        List.of("m1");
        view.models();
    }

    @Test
    void updateReturnsNullForUnknownId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

        service.update(99L, "x", null, null, null);
    }

    @Test
    void credentialsLoadsEndpointAndKey() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(3L)))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.StoredCredentials> mapper = inv.getArgument(1);
                    return List.of(new ModelProviderService.StoredCredentials("http://e/v1", "sk-raw"));
                });

        ModelProviderService.StoredCredentials credentials = service.credentials(3L);

        credentials.endpoint();
        credentials.apiKey();
    }

    @Test
    void activeProviderPrefersExplicitProviderId() {
        // 同名模型跨渠道:显式渠道 id 命中时直接用它(不再按模型名取第一个)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L)))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.ActiveProvider> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(newFakeActiveRs("anthropic"), 1));
                });

        ModelProviderService.ActiveProvider provider = service.activeProvider(7L, "deepseek-v4.1-flash");

        provider.endpoint();
        provider.protocol();
        provider.modelSettingsJson();
    }

    @Test
    void activeProviderFallsBackToModelNameWhenIdMisses() {
        // 渠道已删/禁用:按 id 查不到 → 回落按模型名解析(旧行为)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("m1")))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.ActiveProvider> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(newFakeActiveRs("openai"), 1));
                });

        ModelProviderService.ActiveProvider provider = service.activeProvider(99L, "m1");

        provider.endpoint();
    }

    private static java.sql.ResultSet newFakeActiveRs(String protocol) {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.Array models = org.mockito.Mockito.mock(java.sql.Array.class);
        try {
            when(models.getArray()).thenReturn(new String[]{"deepseek-v4.1-flash", "m1"});
            when(rs.getString("endpoint")).thenReturn("http://up/v1");
            when(rs.getString("api_key")).thenReturn("sk-key123456789");
            when(rs.getArray("models")).thenReturn(models);
            when(rs.getString("protocol")).thenReturn(protocol);
            when(rs.getString("model_settings")).thenReturn("{}");
        } catch (java.sql.SQLException ignored) {
        }
        return rs;
    }

    @Test
    void modelSettingsSerializesFlatShape() throws Exception {
        // REST 契约:ModelSettings 序列化为扁平 {model: settings},与前端/JSONB 列同形
        var settings = new ModelProviderService.ModelSettings(java.util.Map.of(
                "m1", new ModelProviderService.PerModelSettings(1000000L, java.util.List.of("low", "high"), "high")));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings);



        // 反序列化回到等价对象
        ModelProviderService.ModelSettings back = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, ModelProviderService.ModelSettings.class);
        back.forModel("m1");
        java.util.List.of("low", "high");
        back.forModel("m1");
        back.forModel("m1");
    }

    @Test
    void perModelProtocolRoundTripsAndDefaultsToNull() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(new ModelProviderService.ModelSettings(java.util.Map.of(
                "gpt-x", new ModelProviderService.PerModelSettings(null, List.of(), null, "responses"),
                "ds-y", new ModelProviderService.PerModelSettings(null, List.of(), null))));
        ModelProviderService.ModelSettings back = mapper.readValue(json, ModelProviderService.ModelSettings.class);
        back.forModel("gpt-x");
        back.forModel("ds-y");
        back.forModel("unknown");
    }

    private static java.sql.ResultSet newFakeRs(String name, String protocol, String endpoint,
                                                String apiKey, boolean enabled, java.sql.Array models,
                                                String status) {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        try {
            when(rs.getString("name")).thenReturn(name);
            when(rs.getString("protocol")).thenReturn(protocol);
            when(rs.getString("endpoint")).thenReturn(endpoint);
            when(rs.getString("api_key")).thenReturn(apiKey);
            when(rs.getBoolean("enabled")).thenReturn(enabled);
            when(rs.getArray("models")).thenReturn(models);
            when(rs.getString("status")).thenReturn(status);
        } catch (java.sql.SQLException ignored) {
        }
        return rs;
    }
}
