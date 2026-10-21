-- 同名文档跨库不再互相覆盖(2026-09-22,阶段 B 修复)。
--
-- 问题:资料库引入后,按名索引(source_id IS NULL)的去重键仍是
-- (source, name)——同一篇文档索引进第二个资料库时,publish 把它当
-- 「同名覆盖」软删了第一个库里的行(实测:plain 库与 parent_child 库
-- 放同名评测语料,后建的库把先建的库清空了)。
--
-- 修复:去重键加 base_id 维度——同一库内同名覆盖(原语义保留),
-- 不同库各自独立(同名文档可以同时存在于多个库)。
DROP INDEX IF EXISTS schema_rag.idx_doc_source_name;
CREATE UNIQUE INDEX idx_doc_source_name ON schema_rag.knowledge_doc (source, name, base_id)
    WHERE source_id IS NULL AND deleted_at IS NULL AND status = 'indexed';
