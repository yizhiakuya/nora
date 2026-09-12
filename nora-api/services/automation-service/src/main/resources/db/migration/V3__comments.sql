-- 表/列注释(schema_automation):Schema 浏览页直接展示。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE automation_rule IS '自动任务规则:触发条件 + 执行动作(Quartz 调度)';
COMMENT ON COLUMN automation_rule.id IS '主键';
COMMENT ON COLUMN automation_rule.name IS '规则名称';
COMMENT ON COLUMN automation_rule.trigger_type IS '触发类型:cron/interval/manual';
COMMENT ON COLUMN automation_rule.trigger_expr IS '触发表达式(如 cron 串)';
COMMENT ON COLUMN automation_rule.action IS '执行动作(JSON:动作类型与参数)';
COMMENT ON COLUMN automation_rule.enabled IS '是否启用';
COMMENT ON COLUMN automation_rule.status IS '运行状态';
COMMENT ON COLUMN automation_rule.last_run_at IS '最近执行时间';
COMMENT ON COLUMN automation_rule.created_at IS '创建时间';
COMMENT ON COLUMN automation_rule.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE execution_record IS '任务执行记录:每次触发的结果与耗时';
COMMENT ON COLUMN execution_record.id IS '主键';
COMMENT ON COLUMN execution_record.rule_id IS '所属规则';
COMMENT ON COLUMN execution_record.duration_ms IS '执行耗时(毫秒)';
COMMENT ON COLUMN execution_record.status IS '执行状态:success/error';
COMMENT ON COLUMN execution_record.detail IS '执行详情/错误信息';
COMMENT ON COLUMN execution_record.started_at IS '开始时间';
