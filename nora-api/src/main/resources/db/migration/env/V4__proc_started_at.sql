-- PROC 源补充:真实启动时间(卡片 uptime 用);环境变量(JSON object,后续注入 ProcessBuilder)
ALTER TABLE managed_source ADD COLUMN started_at TIMESTAMPTZ;
