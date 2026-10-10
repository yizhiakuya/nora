ALTER TABLE chat_run ADD COLUMN context_usage jsonb;
COMMENT ON COLUMN chat_run.context_usage IS '最近模型请求的完整输入、输出、缓存计量与整理预算；估算和上游计量分别标明来源';
