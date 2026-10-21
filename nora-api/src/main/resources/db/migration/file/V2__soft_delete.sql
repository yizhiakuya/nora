-- 软删除:file_item 改 UPDATE 标记删除(2026-09-12 用户要求,全库统一方案)。
-- deleted_at IS NULL = 存活;有值 = 删除时间。
-- 磁盘上的原始文件不删除(数据不真丢);代价是磁盘占用,后续可加回收站清理。
ALTER TABLE file_item ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
