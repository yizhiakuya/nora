-- 修正预置 FILE 源的日志路径(V1 种子 + megumin 部署时代的遗留值均已失效):
--   1) V1 种子指向仓库根 D:\claude\Nora\<svc>-service.stdout.log(旧启动约定,服务已改从 nora-api/ 启动)
--   2) megumin 部署时被改为 /opt/nora/logs/<svc>.out.log(Linux 路径,该部署已拆除)
-- 当前规范位置 = logback 双文件输出的人读文本 D:\claude\Nora\logs\<name>-text.log
-- (LOG_PATH 默认;与启动方式无关,IDE/systemd/手动 java -jar 都持续写入)。
-- 仅当路径仍是上述失效模式时更新,不覆盖用户自定义路径。
UPDATE managed_source
SET file_log_path = 'D:\claude\Nora\logs\' || name || '-text.log'
WHERE kind = 'FILE'
  AND deleted_at IS NULL
  AND name IN ('gateway-service', 'agent-service', 'rag-service', 'datasource-service',
               'env-service', 'file-service', 'automation-service')
  AND (
      file_log_path LIKE '/opt/nora/logs/%'
      OR file_log_path LIKE 'D:\claude\Nora\%.stdout.log'
  );
