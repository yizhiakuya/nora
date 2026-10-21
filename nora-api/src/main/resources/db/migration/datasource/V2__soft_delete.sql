-- 软删除:db_connection 改 UPDATE 标记删除(2026-09-12 用户要求,全库统一方案)。
-- deleted_at IS NULL = 存活;有值 = 删除时间。
-- query_history 无删除入口(审计数据),不加列;连接删除后历史因过滤而不可见,
-- 数据本身保留(原 ON DELETE CASCADE 在软删下不再触发)。
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
