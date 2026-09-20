-- 日程契约(M4-01,2026-09-20,方案 §7.2)。
--
-- 问题:此前每日/每周只按"日期窗口"判断(daily: last_run < 今天 0 点;
-- weekly: last_run < 7 天前),没有具体时刻/星期/时区——界面写"每日 09:00"
-- 只是标签,实际执行时间不可预期(审查报告 B06)。
--
-- 方案:
--   - schedule JSONB 为**唯一权威来源**:{frequency, localTime(HH:mm),
--     dayOfWeek(weekly: 1-7=周一至周日), timezone(IANA)};
--   - next_run_at 为派生字段(仅用于查询/展示,由服务端统一计算写入,
--     与 schedule 保持一致);
--   - configuration_status: ok / needs_config——存量规则无真实时刻的
--     标 needs_config 并暂停,不把旧标签文字当可靠配置(方案 §8.3 第 4 条)。
ALTER TABLE automation_rule ADD COLUMN IF NOT EXISTS schedule JSONB;
ALTER TABLE automation_rule ADD COLUMN IF NOT EXISTS next_run_at TIMESTAMP;
ALTER TABLE automation_rule ADD COLUMN IF NOT EXISTS configuration_status VARCHAR(20) NOT NULL DEFAULT 'ok';

-- 调度扫描:按 next_run_at 找到期规则
CREATE INDEX IF NOT EXISTS idx_rule_next_run ON automation_rule (next_run_at) WHERE deleted_at IS NULL;

COMMENT ON COLUMN automation_rule.schedule IS '日程(权威):frequency/localTime/dayOfWeek/timezone;null=手动或需配置';
COMMENT ON COLUMN automation_rule.next_run_at IS '下一次计划执行(派生,服务端计算;手动试跑不改变它)';
COMMENT ON COLUMN automation_rule.configuration_status IS 'ok/needs_config:存量无时刻规则标需配置并暂停';

-- 存量迁移(方案 §8.3 第 4 条):daily/weekly 旧规则没有真实时刻——
-- 保留内容,暂停并标 needs_config,要求用户补充时间/时区;不静默补默认时刻。
UPDATE automation_rule
SET enabled = false, status = 'paused', configuration_status = 'needs_config'
WHERE trigger_type IN ('daily', 'weekly') AND deleted_at IS NULL AND schedule IS NULL;

-- 存量 file/error 规则:触发方式未实现,标 needs_config(方案 §8.3 第 5 条)
UPDATE automation_rule
SET enabled = false, status = 'paused', configuration_status = 'needs_config'
WHERE trigger_type IN ('file', 'error') AND deleted_at IS NULL;
