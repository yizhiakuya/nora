-- 纳管 FILE 源路径改为「相对文件名」:跨环境部署的根治(2026-10-01 megumin 实测)。
--
-- 背景:V1/V9/V10 种子写死了开发机的绝对路径(D:\claude\Nora\logs\...)。
-- Docker 部署时这些路径全部不存在(日志实际在共享卷 /app/logs),环境控制台
-- 11 个源全显示「日志文件不存在」,agent 诊断时也被误导。
--
-- 修复:把这 8 个服务自身的日志源归一化为**裸文件名**(<name>-text.log),
-- 由 env-service 运行时基于 LOG_PATH 解析成当前环境的绝对路径
-- (见 ManagedSourceService.resolveLogPath)。绝对路径的自定义源不受影响。
--
-- 匹配守卫:只归一化指向这些服务 text/stdout 日志的路径,不碰用户自定义路径。
UPDATE managed_source
SET file_log_path = name || '-text.log'
WHERE kind = 'FILE'
  AND deleted_at IS NULL
  AND name IN ('gateway-service', 'agent-service', 'rag-service', 'datasource-service',
               'env-service', 'file-service', 'automation-service', 'notification-service')
  AND (
      file_log_path LIKE '%' || name || '-text.log'
      OR file_log_path LIKE '%' || name || '.stdout.log'
  );
