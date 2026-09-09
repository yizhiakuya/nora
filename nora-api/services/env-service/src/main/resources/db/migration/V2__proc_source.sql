-- 纳管源扩展:PROC 类型 —— 由平台直接拉起的本地程序(java -jar / node / 任意可执行),
-- env-service 充当进程守护(supervisor):spawn 子进程、stdout/stderr 重定向到受管日志、
-- 崩溃自动拉起(带退避)、支持启停。FILE/DOCKER 语义不变。
ALTER TABLE managed_source DROP CONSTRAINT managed_source_kind_check;
ALTER TABLE managed_source ADD CONSTRAINT managed_source_kind_check
    CHECK (kind IN ('FILE', 'DOCKER', 'PROC'));

ALTER TABLE managed_source
    ADD COLUMN command        TEXT,         -- PROC 源:启动命令(java -jar xx.jar / node server.js …)
    ADD COLUMN work_dir       TEXT,         -- PROC 源:工作目录
    ADD COLUMN pid            BIGINT,       -- PROC 源:最近一次 spawn 的进程 id(重启后失联=null)
    ADD COLUMN desired_state  VARCHAR(16)   NOT NULL DEFAULT 'STOPPED', -- 期望状态 RUNNING/STOPPED,守护调和的目标
    ADD COLUMN last_exit_code INTEGER;      -- 最近一次退出码(正在运行=null)

-- PROC 源必须带启动命令;FILE/DOCKER 不允许设置 command
ALTER TABLE managed_source ADD CONSTRAINT kind_payload_proc CHECK (
    (kind = 'PROC' AND command IS NOT NULL) OR kind <> 'PROC'
);
