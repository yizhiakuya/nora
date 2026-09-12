package com.nora.datasource.controller;

import com.nora.datasource.service.DatasourceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class DatasourceControllerTest {

    @Mock
    private DatasourceServiceImpl service;

    private DatasourceController controller;

    @BeforeEach
    void setUp() {
        controller = new DatasourceController(service);
    }

    @Test
    void healthReturnsOk() {
        controller.health();
    }

    @Test
    void listWrapsConnectionsInEnvelope() {
        List<DatasourceServiceImpl.ConnectionView> views = List.of(
                new DatasourceServiceImpl.ConnectionView(
                        1L, "local-pg", "postgresql", "localhost", 5432,
                        "nora", "nora", "••••••••ra", "connected"));
        when(service.list()).thenReturn(views);

        controller.list();
    }

    @Test
    void queryDelegatesToService() {
        com.nora.datasource.api.QueryResult result =
                new com.nora.datasource.api.QueryResult(List.of("id"), List.of(), 0, 5L);
        when(service.executeReadOnly(3L, "SELECT 1")).thenReturn(result);

        var response = controller.query(3L, new DatasourceController.QueryRequest("SELECT 1"));

        response.data();

    }

    @Test
    void historyDefaultsTo50WhenAbsent() {
        when(service.history(1L, 50)).thenReturn(List.of());

        controller.history(1L, null);


    }

    @Test
    void historyClampsLimits() {
        when(service.history(1L, 200)).thenReturn(List.of());
        when(service.history(1L, 1)).thenReturn(List.of());

        controller.history(1L, 999);
        controller.history(1L, -5);



    }
}
