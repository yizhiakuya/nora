-- 软删除:managed_source 改 UPDATE 标记删除(2026-09-12 用户要求,全库统一方案)。
-- deleted_at IS NULL = 存活;有值 = 删除时间。
-- 唯一名释放:UNIQUE(name) 改为 partial unique index——删掉的源释放名字,可重建同名。
ALTER TABLE managed_source ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE managed_source DROP CONSTRAINT IF EXISTS managed_source_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS managed_source_name_active ON managed_source (name) WHERE deleted_at IS NULL;
