-- 对话运行生命周期(M3-01,2026-09-20,方案 §6.4)。
--
-- 问题:此前"进行中轮次"只存在于进程内(TurnStreamRegistry/activeTurns)——
-- 刷新页面后能找到(前端探测),但**进程重启即全部消失**,任务页无法聚合
-- 「我正在处理什么」,也没有持久化的运行状态可查。
--
-- 方案:每轮开始时落一行 queued/running,结束更新**同一行**为终态
-- (方案 §6.4 约束:不得完成后又插入第二条重复行)。启动恢复把上一进程
-- 遗留的非终态标 interrupted(不自动重放有副作用的任务)。
--
-- 状态集合(方案 §6.3):queued / running / awaiting_approval / cancelling /
-- completed / partial / failed / cancelled / interrupted。
CREATE TABLE chat_run (
    id          VARCHAR(64) PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    status      VARCHAR(24) NOT NULL,
    content     TEXT,
    started_at  TIMESTAMP NOT NULL DEFAULT now(),
    finished_at TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 任务页聚合:按状态过滤 + 时间排序
CREATE INDEX idx_chat_run_status_time ON chat_run (status, started_at DESC);
CREATE INDEX idx_chat_run_session ON chat_run (session_id, started_at DESC);

COMMENT ON TABLE chat_run IS '对话运行生命周期(开始时落行,终态更新同一行;重启恢复标 interrupted)';
COMMENT ON COLUMN chat_run.status IS 'queued/running/awaiting_approval/cancelling/completed/partial/failed/cancelled/interrupted';
