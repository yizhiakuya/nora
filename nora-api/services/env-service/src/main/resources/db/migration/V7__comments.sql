-- 表/列注释(schema_env):Schema 浏览页直接展示。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE managed_source IS '纳管源:Docker 容器 / 日志文件 / 托管进程(PROC 崩溃自愈)';
COMMENT ON COLUMN managed_source.id IS '主键';
COMMENT ON COLUMN managed_source.kind IS '类型:CONTAINER/FILE/PROC';
COMMENT ON COLUMN managed_source.name IS '展示名';
COMMENT ON COLUMN managed_source.file_log_path IS '日志文件路径(FILE 型)';
COMMENT ON COLUMN managed_source.container_name IS '容器名(CONTAINER 型)';
COMMENT ON COLUMN managed_source.enabled IS '是否纳管';
COMMENT ON COLUMN managed_source.created_at IS '创建时间';
COMMENT ON COLUMN managed_source.command IS '启动命令(PROC 型)';
COMMENT ON COLUMN managed_source.work_dir IS '工作目录(PROC 型)';
COMMENT ON COLUMN managed_source.pid IS '当前进程 pid(PROC 型)';
COMMENT ON COLUMN managed_source.desired_state IS '期望状态:running/stopped(自愈依据)';
COMMENT ON COLUMN managed_source.last_exit_code IS '最近退出码';
COMMENT ON COLUMN managed_source.started_at IS '启动时间';
COMMENT ON COLUMN managed_source.env_vars IS '环境变量(JSON)';
COMMENT ON COLUMN managed_source.deleted_at IS '软删除时间;NULL=存活';
