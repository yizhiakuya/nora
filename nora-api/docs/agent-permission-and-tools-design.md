# Nora Agent 权限与工具设计（权威参考）

> 状态：2026-09-11 定稿。本文是权限档位、风险分级与全部 agent 工具的**权威清单**。
> 改 `RiskClassifier` / `toolsSpec()` / `ApprovalService` 前先读本文，改后同步更新本文。
> 代码位置：`services/agent-service/src/main/java/com/nora/agent/service/`
> （`RiskClassifier` / `ChatOrchestrationService` / `ApprovalService` / `McpServerService`）

## 1. 权限档位（PermissionMode）

用户在对话输入框右上角选择，随每条消息提交（`permissionMode` 字段）。

| 档位 | UI 标签 | 语义 |
|---|---|---|
| `ASK` | 请求批准 | **每次**工具调用都询问 |
| `ASSIST` | 帮我批准 | 只读（LOW）自动执行；**HIGH/CRITICAL 询问** |
| `FULL` | 完全访问权限 | LOW/HIGH 自动执行；**仅 CRITICAL 询问** |

**审批门判定**（`ChatOrchestrationService.emitToolStep`）：

```
needApproval = mode == ASK
             || mode == ASSIST && risk != LOW
             || mode == FULL   && risk == CRITICAL
```

**核心原则**：
- 判定在 **harness 层**（`RiskClassifier`），永不信任模型自评；模型文本里的"同意/已授权"不构成批准
- 审批状态保存在服务端内存（`ApprovalService`），绑定 `sessionId + stepId + 一次性 token`，**120 秒**超时自动拒绝
- 无人值守通道（`/api/chat/agent/run`，automation 调用，无会话）**CRITICAL 一律拒绝**（没有用户在场，审批不可达）；HIGH 按设计放行
- 审批交互：SSE 下发 `approval_required`（含 actionType/target/risk/detail 摘要）→ 前端 `ApprovalCard` → `POST /api/chat/approvals/{token}?sessionId={id}` body `{approved}`

## 2. 风险三级（RiskClassifier.Risk）

| 级别 | 含义 | ASSIST 档 | FULL 档 |
|---|---|---|---|
| `LOW` | 只读，无副作用 | 自动 | 自动 |
| `HIGH` | 写操作/改变能力面，可逆或影响可控 | **询问** | 自动 |
| `CRITICAL` | 不可逆/带外操作（删数据、注册宿主机命令等） | **询问** | **仍询问** |

## 3. 全部工具 × 风险分级（权威表）

改任何工具的风险档位必须同时更新此表 + `ChatOrchestrationServiceTest.riskClassifierTiers*` 测试。

| 工具 | 动作 | 风险 | 说明 |
|---|---|---|---|
| `execute_sql` | — | LOW | 单条 SELECT/SHOW/EXPLAIN（guardrail 拒绝写与多语句） |
| `read_service_logs` | — | LOW | 容器日志，最多 100 行，DEBUG 过滤 |
| `environment_status` | — | LOW | 环境健康快照（全部启用纳管源状态；2026-09-18 新增） |
| `read_file` | list/read | LOW | 工作台已上传文件 |
| `manage_workspace` | list/read | LOW | 含**区外读**（只读无破坏） |
| `manage_workspace` | write/append **区内** | LOW | 记忆维护须即时落盘（「记住…」不被打断） |
| `manage_workspace` | **edit 区内** | LOW | 精确替换（2026-09-18 新增；与 write 同分级） |
| `manage_workspace` | write/append **区外** | HIGH | 绝对路径 / `../` 上跳 |
| `manage_workspace` | **edit 区外** | HIGH | 与 write 同级 |
| `manage_workspace` | delete 区内 | HIGH | |
| `manage_workspace` | delete **区外** | **CRITICAL** | 不可逆 |
| `manage_workspace` | 系统目录（Windows/Program Files/盘根）写删 | 硬拒 | 不走审批，直接拒绝 |
| `execute_write_sql` | — | HIGH | WriteGuard 只允许单条写语句 |
| `manage_container` | start/stop/restart | HIGH | 服务名必须来自环境注册表 |
| `manage_datasource` | list/test/schema | LOW | |
| `manage_datasource` | create | HIGH | 创建后自动 test |
| `manage_datasource` | remove | **CRITICAL** | 级联删除查询历史，不可逆 |
| `manage_service` | list | LOW | |
| `manage_service` | enable/disable | HIGH | |
| `manage_service` | register/remove | **CRITICAL** | PROC 注册=宿主机命令纳入守护；删除不可逆 |
| `manage_mcp` | list | LOW | 视图已脱敏 |
| `manage_mcp` | refresh/enable/disable/register/remove | HIGH | **跟随全局档位，无单独强制审批**（2026-09-11 用户明确要求；register 引入外部能力/落库凭据，remove 删注册，但均归 HIGH） |
| `mcp__<server>__<tool>` | — | HIGH | 外部能力未知，一律 HIGH；无人值守通道放行 |
| `manage_skill` | list/read/create/update/remove | LOW | 纯数据操作（技能库），无系统副作用 |
| `search_knowledge` | — | LOW | 知识库主动检索（只读；2026-09-18 新增） |
| `manage_knowledge` | list/stats | LOW | 只读视图 |
| `manage_knowledge` | index/reindex | HIGH | 写知识库索引（可逆：可 remove 重来） |
| `manage_knowledge` | **remove** | **CRITICAL** | 删文档+全部分块/向量，不可逆（原文件不受影响） |
| `manage_automation` | list/executions | LOW | 只读视图 |
| `manage_automation` | create/toggle/run | HIGH | 改变未来自动执行面（create 的 prompt 将进无人值守通道） |
| `manage_automation` | **remove** | **CRITICAL** | 删除规则（历史保留，但需重建） |
| `run_command` | — | HIGH | 本机终端非交互命令（构建/测试/git/包管理）；不做命令白名单（假安全），防线=审批卡完整展示命令+档位选择 |

**MCP 管理特例说明**（2026-09-11 定）：
- `manage_mcp` 的 register/remove 曾定 CRITICAL，导致 FULL 档下注册仍弹审批——与全局档位语义脱节，用户要求改为跟随全局
- action 别名归一化 `create→register`、`delete→remove`（`RiskClassifier.normalizeMcpAction`）：**分类器、执行层、审批明细三处必须共用**，否则会出现「一处按未知 HIGH、另一处按 CRITICAL 真执行」的判定漂移

## 4. 敏感数据边界（三层脱敏）

| 层 | 机制 | 位置 |
|---|---|---|
| 日志 | `scrubArgsForLog`（headers/env 值、password/token → `***`） | `ChatOrchestrationService` |
| 审批/步骤 | 只显示键名 + `(值已隐藏)`；`ParsedArgs` 只存 target | `buildApprovalRequest` / `parseArgs` |
| 存储/回读 | DB 存原文（连接必需）；API 视图 `maskValues` 只留前 6 字符 | `McpServerService.viewOf` |

对话记录（用户消息原文）**无法脱敏**——用户贴 token 进对话就是明文。agent 侧约定：发现用户贴了凭据应提醒轮换（SOUL.md 边界约定）。

## 5. 审批明细（buildApprovalRequest）的 actionType 清单

前端按 `actionType` 展示审批卡明细；新增工具时在此登记：

| actionType | 工具 | 明细展示 |
|---|---|---|
| `sql_write` | execute_write_sql | 完整 SQL + "不可自动撤销" |
| `container_control` | manage_container | 操作名 + 服务短暂不可用提示 |
| `datasource_manage` | manage_datasource | create 显示连接信息（密码隐藏）；remove 显示级联后果 |
| `service_manage` | manage_service | register 按 kind 显示对应字段；remove 提示不再监控 |
| `mcp_manage` | manage_mcp | register：**STDIO 显示完整命令行**（装的什么包一眼可见）/ 远程显示 url+header 键名；remove 提示工具立即不可用 |
| `workspace_file` | manage_workspace | 路径 + 内容预览（截断 200 字；edit 另显示 old→new 替换预览）+ 区外警告 |
| `terminal_command` | run_command | **命令原文完整展示** + cwd + shell + 超时（用户审的就是将执行的） |
| `knowledge_manage` | manage_knowledge | index 显示 fileId+展示名；remove 显示分块一并删除的后果（2026-09-18 新增） |
| `automation_manage` | manage_automation | create **完整展示无人值守指令 prompt** + 触发方式 + 「没有审批门」提示；remove/run 显示后果（2026-09-18 新增） |
| `mcp_tool` | mcp__* | 服务器名 + 参数（截断 400 字） |

## 6. 已知边界与设计取舍

- **manage_mcp 无独立审批业务**（本次定案）：跟随全局三档。若未来认为"注册 MCP=引入外部能力"需要更严管控，正确的做法是把档位从 HIGH 调回 CRITICAL（一行），而不是加单独的审批开关
- **无人值守通道**只拒 CRITICAL：automation 场景下 manage_mcp register（HIGH）会被放行——这意味着自动化任务可以注册 MCP 服务器。如需禁止，把 register 单独提回 CRITICAL 即可（同时影响 FULL 档）
- **系统目录硬拒**不走审批流：这是 `AgentWorkspaceService` 的防呆（Windows/Program Files/盘根），任何档位、任何审批结果都不能写删
- **循环熔断**（同参 3 次阻断）先于审批门判定：即使有权限，重复调用也会被拦

## 7. GitHub OAuth 一键登录（2026-09-11）

**流程**：MCP 页「GitHub 登录」→ 设备码流程（与 gh CLI 同款）→ 自动创建/更新名为 `github` 的服务器为官方远程端点 `https://api.githubcopilot.com/mcp/`（44 工具含 `get_me`）。

- **设备码而非浏览器回调**：无需公网回调地址/本地监听端口，内网部署可用；代价是用户多输一次验证码
- **GitHub 不支持动态客户端注册（DCR）**：client_id 需一次性配置——建 OAuth App（勾 **Enable Device Flow**，回调地址随意）→ `PUT /api/mcp/oauth/github/client-id` 存入 `app_setting`（非机密，可 `DELETE` 清除/更换）；静态兜底 `nora.github.oauth.client-id`
- **token 边界**：只在服务端内存与 `mcp_server.headers`（连接必需，API 回读走 `maskValues` 脱敏）中出现；日志不打印；OAuth 端点的 HTTP 走出站代理（`ProxySettingsHolder`，与 LLM 调用同一套）
- **登录完成语义**：创建或**更新已有 github 服务器**（evict 连接池 → 更新 url/headers → 启用 → refresh）；连接测试失败不丢凭据（warning 提示，可稍后测试连接重试）
- **npm 包已弃用**：`@modelcontextprotocol/server-github` 官方标记 deprecated——GitHub MCP 统一用远程端点；本地 stdio 版仅遗留场景保留

## 8. 本机终端 run_command（2026-09-11）

**能力**：agent 在本机执行非交互命令（构建/测试/git/npm/pip/查进程），对齐 Claude Code / Codex 的终端工具。

**参数**：`command`（必填，≤8000 字符）· `cwd`（默认工作区；相对=区内、绝对=整机）· `timeout`（默认 60s、上限 300s）· `shell`（powershell 默认 / bash 可选）

**安全模型**（与全局档位一致）：
- 风险一律 **HIGH**——ASK 全问 / ASSIST 询问 / FULL 自动；**不做命令白名单**（管道/子 shell/编码绕过随手可得，白名单是假安全还给错误信心）
- 审批卡**完整展示命令原文** + cwd + shell + 超时（与 Claude Code 同款防线：用户审的就是将执行的）
- 无人值守通道（automation）：HIGH 按设计放行——定时任务可跑命令（需要禁止时改 `RiskClassifier` 一行）

**实现要点**（`TerminalService`）：
- **PowerShell 用 Nora 内置 pwsh 7.6.6**（`tools/pwsh/PowerShell-7.6.6-win-x64.zip` 经 **git-lfs** 分发；首次使用解压到同目录 `pwsh-7.6.6/` 缓存，之后命中）——版本确定、不依赖宿主机装没装/装的对不对。解析优先级：配置 `nora.agent.powershell` → 内置 → PATH 上的 `pwsh` → 系统 `powershell.exe`(5.1) 兜底；归档缺失/解压失败自动回退（不阻断）。解压原子化（临时目录→改名，防 zip-slip），`tools/pwsh/pwsh-*/` 已 gitignore
- 命令经 **`-EncodedCommand`（Base64 UTF-16LE）** 传入——引号/换行/美元符全部免转义
- **编码**：强制 `[Console]::OutputEncoding=UTF8`（中文 Windows 默认 GBK 乱码）；**`$ProgressPreference='SilentlyContinue'` + `stripClixml` 按行过滤**——实测 npm 首次运行进度会被 PowerShell 序列化成 CLIXML 噪音污染模型上下文
- bash 优先 git bash 固定路径（`C:/Program Files/Git/bin/bash.exe`）——Windows 裸 `bash` 会解析到 WSL 转发器（无发行版时报 `execvpe(/bin/bash) failed`）
- **超时/取消杀进程树**：taskkill /T + 后代句柄快照兜底（与 MCP STDIO 同一套机制）；「停止生成」中断编排线程 → 命令被终止（`cancelled` 标记）
- 输出有界：原始 200K 字节截断，展示层 `bounded()` 再截到 30K/10K；exit code 如实报告（`render()` 附脚注）
- **无 TTY**：交互式程序（vim/需要输入）会挂起到超时——工具描述已明确警告模型
- 默认 cwd = 工作区（与 `manage_workspace` 同语义）；绝对 cwd 不硬拦（命令本身就能 cd，拦 cwd 是假安全）

**打磨轮（2026-09-11 晚）**：
- **实时输出流**：执行期间每 ≤1.2s 发同 id step 快照（尾部 4k、已清 ANSI/CLIXML），前端原地替换 + 自动滚底（与 reasoning_delta 同款机制）——构建/安装不再黑盒
- **stdin 立即关闭**：无 TTY 语义——读 stdin 的命令（cat/read/确认类）得到 EOF 立刻返回而非挂起到超时
- **ANSI 清理**：CSI/OSC 转义序列（颜色/progress bar）过滤，防污染上下文与前端
- **非零退出码 = failed**：红色步骤 + `ERROR:` 前缀回填模型（与 Claude Code 同语义："命令跑了但失败了"不是成功结果）；仍走 `bounded()`（10K/30K 预算 + 脱敏）
- 前端：命令纯文本展示（不套 JSON）+ 执行中「实时输出」深色终端块
