-- 表/列注释(schema_datasource):Schema 浏览页直接展示,让表结构带上语义。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE db_connection IS '数据源连接:可被 AI 读取 Schema 辅助生成 SQL';
COMMENT ON COLUMN db_connection.id IS '主键';
COMMENT ON COLUMN db_connection.name IS '连接名(展示用)';
COMMENT ON COLUMN db_connection.engine IS '引擎:postgresql/mysql';
COMMENT ON COLUMN db_connection.host IS '主机';
COMMENT ON COLUMN db_connection.port IS '端口';
COMMENT ON COLUMN db_connection.database IS '数据库名';
COMMENT ON COLUMN db_connection.username IS '用户名';
COMMENT ON COLUMN db_connection.password IS '密码(明文;API 不回传,仅掩码展示)';
COMMENT ON COLUMN db_connection.status IS '连通状态:connected/error/untested';
COMMENT ON COLUMN db_connection.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE query_history IS '查询历史:只读查询与审批后写操作的执行记录(审计)';
COMMENT ON COLUMN query_history.id IS '主键';
COMMENT ON COLUMN query_history.connection_id IS '所属连接';
COMMENT ON COLUMN query_history.sql_text IS 'SQL 原文';
COMMENT ON COLUMN query_history.duration_ms IS '执行耗时(毫秒)';
COMMENT ON COLUMN query_history.rows_affected IS '影响/返回行数';
COMMENT ON COLUMN query_history.status IS '执行状态:success/error';
COMMENT ON COLUMN query_history.executed_at IS '执行时间';
