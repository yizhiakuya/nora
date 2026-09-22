-- 构建诊断(2026-09-22,知识库优化阶段 A):失败原因与解析告警对用户可见。
--
-- error:  构建/发布失败的可读原因(status='failed' 时展示,恢复后清空);
-- warning: 非致命告警(如解析超限仅索引前 50 万字符),文档仍可检索。
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS error TEXT;
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS warning TEXT;

COMMENT ON COLUMN knowledge_doc.error IS '构建失败原因(阶段 A:先构建后发布,失败行可见可重试)';
COMMENT ON COLUMN knowledge_doc.warning IS '非致命解析告警(如超限截断)';
