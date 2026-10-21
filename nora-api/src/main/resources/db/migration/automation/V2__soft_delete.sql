-- 软删除:automation_rule 改 UPDATE 标记删除(2026-09-12 用户要求,全库统一方案)。
-- deleted_at IS NULL = 存活;有值 = 删除时间。
-- execution_record 无删除入口(审计数据),不加列;规则软删后历史记录保留
-- (原 ON DELETE CASCADE 在软删下不再触发,执行历史天然保全)。
ALTER TABLE automation_rule ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
