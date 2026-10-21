-- 对话保存成果登记(B1,2026-09-27,评审报告:成果入口和实际保存行为没有对齐)。
--
-- 问题:对话「保存为文件」写到工作区 reports/…md、「保存到知识库」进 RAG,
-- 但「已保存状态」只存在浏览器 localStorage——换浏览器就不知道哪些回答
-- 保存过,也无法从资料页找到「这个文件来自哪次对话」。浏览器缓存不应
-- 承担成果归属的权威记录(评审报告 §B1)。
--
-- 方案:保存成功后向本表登记一条(服务端权威):
--   - kind = workspace_file | knowledge_doc
--   - path:工作区相对路径(workspace_file)或知识文档 id 字符串(knowledge_doc)
--   - session_id / message_key:来源会话与消息(contentKey,回到来源用)
-- 内容不复制第三份——正文仍在工作区/知识库,这里只存归属与定位关系。
CREATE TABLE saved_artifact (
    id          BIGSERIAL PRIMARY KEY,
    kind        VARCHAR(20) NOT NULL,
    path        VARCHAR(500) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    session_id  VARCHAR(64),
    message_key VARCHAR(64),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 同一 (kind, path) 只保留最新一条:重复保存同一回答会覆盖同一路径,
-- 登记也随之更新(幂等;UNIQUE 供 upsert)
CREATE UNIQUE INDEX uq_saved_artifact_kind_path ON saved_artifact (kind, path);
CREATE INDEX idx_saved_artifact_created ON saved_artifact (created_at DESC);

COMMENT ON TABLE saved_artifact IS '对话保存成果登记(服务端权威:哪个文件/文档来自哪次对话)';
COMMENT ON COLUMN saved_artifact.kind IS 'workspace_file(工作区文件)/ knowledge_doc(知识库文档)';
COMMENT ON COLUMN saved_artifact.path IS 'workspace_file=工作区相对路径;knowledge_doc=知识文档 id';
COMMENT ON COLUMN saved_artifact.session_id IS '来源会话 id(回到来源会话用)';
COMMENT ON COLUMN saved_artifact.message_key IS '来源消息键(前端 contentKey;同回答重复保存时定位同一条)';
