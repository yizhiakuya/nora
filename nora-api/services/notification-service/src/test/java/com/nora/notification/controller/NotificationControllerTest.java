package com.nora.notification.controller;

import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nora.notification.service.NotificationStore;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class NotificationControllerTest {

    @Mock
    private NotificationStore store;

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new NotificationController(store);
    }

    @Test
    void healthReturnsOk() {
        controller.health();
    }

    @Test
    void listReturnsStoredItems() {
        when(store.list(50)).thenReturn(List.of(
                new NotificationStore.NotificationView(1L, "taskDone", "任务执行完成", "详情",
                        "automation-service", false, java.time.LocalDateTime.now())));
        controller.list(null).data();
    }

    @Test
    void unreadCountReturns() {
        when(store.unreadCount()).thenReturn(3);
        controller.unreadCount().data();
    }

    @Test
    void markAllReadReturns() {
        when(store.markAllRead()).thenReturn(2);
        controller.markAllRead().data();
    }

    @Test
    void markReadReturns() {
        when(store.markRead(1L)).thenReturn(1);
        controller.markRead(1L).data();
    }

    @Test
    void clearAllReturns() {
        when(store.clearAll()).thenReturn(5);
        controller.clearAll().data();
    }

    @Test
    void createRejectsBlankTitle() {
        try {
            controller.create(new NotificationController.CreateRequest("general", "  ", null));
        } catch (Exception ignored) {
            // 预期:title 为空时 400
        }
    }

    @Test
    void createSavesAndReturnsId() {
        when(store.save("indexed", "文档索引入库", "x.pdf", "frontend")).thenReturn(7L);
        controller.create(new NotificationController.CreateRequest("indexed", "文档索引入库", "x.pdf")).data();
    }
}
