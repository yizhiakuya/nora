-- 纳管 notification-service(2026-09-19 新服务):环境控制台/首页服务面板
-- 应显示全部 8 个微服务。FILE 源,日志走 logback 双文件输出的文本版。
-- 注:managed_source 的唯一索引是部分索引(name WHERE deleted_at IS NULL),
-- ON CONFLICT 必须带 WHERE 才能匹配(V19 踩过的同款坑)。
INSERT INTO managed_source (kind, name, file_log_path)
VALUES ('FILE', 'notification-service', 'D:\claude\Nora\logs\notification-service-text.log')
ON CONFLICT (name) WHERE deleted_at IS NULL DO NOTHING;
