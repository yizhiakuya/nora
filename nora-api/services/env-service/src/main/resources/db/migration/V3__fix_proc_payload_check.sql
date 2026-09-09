-- 修复:V1 的 kind_payload 约束未随 V2 替换,PROC 行(command 有值)被它拒绝。
-- 按 V2 的新语义重建 payload 约束:PROC 必须带 command;FILE/DOCKER 语义不变。
ALTER TABLE managed_source DROP CONSTRAINT kind_payload;
ALTER TABLE managed_source ADD CONSTRAINT kind_payload CHECK (
    (kind = 'FILE'   AND file_log_path  IS NOT NULL AND container_name IS NULL AND command IS NULL) OR
    (kind = 'DOCKER' AND container_name IS NOT NULL AND file_log_path  IS NULL AND command IS NULL) OR
    (kind = 'PROC'   AND command        IS NOT NULL AND file_log_path  IS NULL AND container_name IS NULL)
);
