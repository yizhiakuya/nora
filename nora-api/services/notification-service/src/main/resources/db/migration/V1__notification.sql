-- 通知中心(2026-09-19 Kafka 事件总线):notification-service 的持久化表。
--
-- 数据流:业务服务发事件到 Kafka → notification-service 消费落这里 →
-- 前端从 REST API 拉取。已读状态也在服务端(跨浏览器一致)。
--
-- 事件类型(event)与前端「通知偏好 → 事件开关」同名:
--   taskDone/taskFail(自动任务)/indexed(索引入库)/svcError(服务异常)/general(其他)
CREATE TABLE notification (
    id          BIGSERIAL PRIMARY KEY,
    event       VARCHAR(32)  NOT NULL DEFAULT 'general',
    title       VARCHAR(200) NOT NULL,
    detail      TEXT,
    source      VARCHAR(64),
    event_at    TIMESTAMP    NOT NULL DEFAULT now(),  -- producer 声明的事件时间
    read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- 前端拉取:最近在前(读多写少,单用户量级,索引按时间即可)
CREATE INDEX idx_notification_created ON notification (created_at DESC);

COMMENT ON TABLE notification IS '通知中心:Kafka 事件消费落库(2026-09-19)';
COMMENT ON COLUMN notification.event IS '事件类型:taskDone/taskFail/indexed/svcError/general(与前端偏好开关同名)';
COMMENT ON COLUMN notification.source IS 'producer 服务名(automation-service/env-service/rag-service)';
COMMENT ON COLUMN notification.read IS '已读状态(服务端存,跨浏览器一致)';
