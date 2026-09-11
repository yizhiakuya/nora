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
| `read_file` | list/read | LOW | 工作台已上传文件 |
| `manage_workspace` | list/read | LOW | 含**区外读**（只读无破坏） |
| `manage_workspace` | write/append **区内** | LOW | 记忆维护须即时落盘（「记住…」不被打断） |
| `manage_workspace` | write/append **区外** | HIGH | 绝对路径 / `../` 上跳 |
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
| `workspace_file` | manage_workspace | 路径 + 内容预览（截断 200 字）+ 区外警告 |
| `mcp_tool` | mcp__* | 服务器名 + 参数（截断 400 字） |

## 6. 已知边界与设计取舍

- **manage_mcp 无独立审批业务**（本次定案）：跟随全局三档。若未来认为"注册 MCP=引入外部能力"需要更严管控，正确的做法是把档位从 HIGH 调回 CRITICAL（一行），而不是加单独的审批开关
- **无人值守通道**只拒 CRITICAL：automation 场景下 manage_mcp register（HIGH）会被放行——这意味着自动化任务可以注册 MCP 服务器。如需禁止，把 register 单独提回 CRITICAL 即可（同时影响 FULL 档）
- **系统目录硬拒**不走审批流：这是 `AgentWorkspaceService` 的防呆（Windows/Program Files/盘根），任何档位、任何审批结果都不能写删
- **循环熔断**（同参 3 次阻断）先于审批门判定：即使有权限，重复调用也会被拦
