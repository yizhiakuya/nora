# 平台原生命令工具

`sess-1791419046617` 在 Linux 容器里先调用两次 `run_command(shell=powershell)`，尝试启动不存在的 `powershell.exe`，失败后才切换 bash。工具描述写死 Windows，且把平台选择交给模型。

由现有 `TerminalService` 根据实际 OS 确定工具与执行器：Windows 仅注册 `powershell`，使用现有 PowerShell 启动链；Linux/macOS 仅注册 `sh`，使用 `/bin/sh -c`。工具仅接收 `command/cwd/timeout`，执行 API 删除 shell 参数。注册、上下文、分发、审批及并发分类共用这一平台判断；不增加第二套执行器或旧工具执行别名。

当前 OS、架构、工具名和工作区根注入系统上下文，说明命令执行范围是 Agent 所在环境，容器结果不能当作宿主机状态。工具说明和使用手册同步更新。旧聊天中的 `run_command` 保留原始历史与命令展示。

验证：既有冒烟测试覆盖执行、超时、输出流、审批及并发链路；真实 Windows 与 megumin Linux 会话执行原生命令，确认工具名、无 shell 参数、输出、持久化和前端展示。

已验收并部署到 megumin，发布 `20261008-084113`：Windows 未指定 shell 自动调用 `powershell`（PowerShell 7.6.6），Linux 自动调用 `sh`（实际进程 `/usr/bin/dash`）。两端同轮三条命令真实并发、单项失败隔离、ASK 逐项审批和取消均通过；原会话与模型配置保持完整。

后端 152 项及公共模块 9 项冒烟测试通过；前端类型检查、Lint、126 项冒烟测试及构建通过。复验命令：`python scripts/e2e-agent-tool-batch.py --case platform`；Linux 服务传 `--tool sh`，网关认证从 `NORA_AUTH_TOKEN` 环境变量读取。完整记录见 `logs/native-terminal-20261008/`。
