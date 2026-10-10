-- 平台决定命令工具,同步内置手册;保留其余正文与用户配置。
UPDATE agent_skill
SET instructions = replace(replace(replace(replace(instructions,
        'run_command(本机终端命令)', 'powershell/sh(系统仅注册当前平台工具,不接受 shell 参数)'),
        '不要借 run_command 的 PowerShell', '不要借终端命令处理文件'),
        '不要用 run_command 逐个 URL 下载', '不要用终端命令逐个 URL 下载'),
        'run_command(工作目录用 cwd 参数,构建类命令给足 timeout)',
        '当前平台命令工具 powershell/sh(以注册清单为准,工作目录用 cwd,构建类命令给足 timeout)'),
    updated_at = now()
WHERE name = '工作台使用手册'
  AND instructions LIKE '%run_command%';
