package com.nora.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        assertEquals("sk-1••••••••4821", ModelProviderService.mask("sk-1234567890abcdef4821"));
        assertEquals("••••••••", ModelProviderService.mask("short"));
        assertEquals("—", ModelProviderService.mask(null));
        assertEquals("—", ModelProviderService.mask(""));
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

        assertEquals(7L, view.id());
        assertEquals("sk-k••••••••6789", view.masked());
        assertEquals("untested", view.status());
        assertEquals(List.of("gpt-5.4-mini"), view.models());
        assertEquals(List.of("low", "high"), view.modelSettings().forModel("gpt-5.4-mini").reasoningLevels());
        assertEquals("high", view.modelSettings().forModel("gpt-5.4-mini").defaultReasoningLevel());
        assertEquals(1_000_000L, view.modelSettings().forModel("gpt-5.4-mini").contextWindow());
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

        assertEquals("New", view.name());
        assertEquals("ok", view.status());
        assertEquals(List.of("m1"), view.models());
    }

    @Test
    void updateReturnsNullForUnknownId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

        assertNull(service.update(99L, "x", null, null, null));
    }

    @Test
    void credentialsLoadsEndpointAndKey() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(3L)))
                .thenAnswer(inv -> {
                    RowMapper<ModelProviderService.StoredCredentials> mapper = inv.getArgument(1);
                    return List.of(new ModelProviderService.StoredCredentials("http://e/v1", "sk-raw"));
                });

        ModelProviderService.StoredCredentials credentials = service.credentials(3L);

        assertEquals("http://e/v1", credentials.endpoint());
        assertEquals("sk-raw", credentials.apiKey());
    }

    @Test
    void modelSettingsSerializesFlatShape() throws Exception {
        // REST 契约:ModelSettings 序列化为扁平 {model: settings},与前端/JSONB 列同形
        var settings = new ModelProviderService.ModelSettings(java.util.Map.of(
                "m1", new ModelProviderService.PerModelSettings(1000000L, java.util.List.of("low", "high"), "high")));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"m1\""), "flat key per model: " + json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"models\""), "no wrapper key: " + json);

        // 反序列化回到等价对象
        ModelProviderService.ModelSettings back = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, ModelProviderService.ModelSettings.class);
        assertEquals(1000000L, back.forModel("m1").contextWindow());
        assertEquals(java.util.List.of("low", "high"), back.forModel("m1").reasoningLevels());
        assertEquals("high", back.forModel("m1").defaultReasoningLevel());
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
