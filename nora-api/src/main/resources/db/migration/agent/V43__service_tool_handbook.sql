-- 只更新内置手册的旧入口指引；用户自定义技能与历史迁移保持原样。
UPDATE agent_skill
SET instructions = replace(replace(replace(instructions,
        'environment_status', 'manage_service action=status'),
        'read_service_logs', 'manage_service action=logs'),
        'manage_container', 'manage_service action=start/stop/restart'),
    updated_at = now()
WHERE name = '工作台使用手册' AND deleted_at IS NULL
  AND (instructions LIKE '%environment_status%' OR instructions LIKE '%read_service_logs%'
       OR instructions LIKE '%manage_container%');
