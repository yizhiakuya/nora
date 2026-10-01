---
name: nora-agent-tools
description: Nora Agent 业务设计权威参考——权限三档(ASK/ASSIST/FULL)、风险三级(LOW/HIGH/CRITICAL)、全部 agent 工具的风险分级、审批流协议、敏感数据脱敏边界。改 RiskClassifier/toolsSpec/审批相关代码前必读,改后必须同步本文件与权威文档。
---

# Nora Agent 业务设计:权限 · 风险 · 工具

**权威文档**(随代码提交,改代码必须同步):
`nora-api/docs/agent-permission-and-tools-design.md`

本 skill 是快速查阅版。发生冲突时以权威文档 + 代码为准,并修正本文件。

## 权限三档(用户对话输入框选择,随消息提交)

| 档位 | UI 标签 | 行为 |
|---|---|---|
| ASK | 请求批准 | 每次工具调用都问 |
| ASSIST | 帮我批准 | LOW 自动;**HIGH/CRITICAL 询问** |
| FULL | 完全访问权限 | LOW/HIGH 自动;**仅 CRITICAL 询问** |

判定公式(harness 层,不信任模型自评):
```
needApproval = ASK || (ASSIST && risk != LOW) || (FULL && risk == CRITICAL)
```

- 无人值守通道(`/api/chat/agent/run`,无会话):**CRITICAL 一律拒绝**,HIGH 放行
- 审批:服务端内存 token(sessionId+stepId),**120s 超时自动拒绝**;模型说"同意"不算批准
- 系统目录(Windows/Program Files/盘根)写删**硬拒**——不走审批,任何档位都拒绝

## 风险分级速查表(2026-09-18 更新)

| 工具 | 动作 | 风险 |
|---|---|---|
| execute_sql / read_service_logs | — | LOW |
| manage_file | list/read/folders | LOW(文件中心只读;2026-09-20 由 read_file 更名,旧名兼容别名) |
| manage_file | import/rename/move/delete/mkdir | HIGH(文件中心管理面;delete=软删可恢复) |
| environment_status | — | LOW(环境健康快照) |
| search_knowledge | — | LOW(知识库主动检索) |
| manage_workspace | 读(含区外读) | LOW |
| manage_workspace | 区内写/追加/edit | LOW(记忆维护即时落盘) |
| manage_workspace | 区外写/追加/edit、区内删 | HIGH |
| manage_workspace | **区外删** | **CRITICAL** |
| execute_write_sql / manage_container | — | HIGH |
| manage_datasource | list/test/schema | LOW |
| manage_datasource | create | HIGH |
| manage_datasource | **remove** | **CRITICAL** |
| manage_service | list | LOW |
| manage_service | enable/disable | HIGH |
| manage_service | **register/remove** | **CRITICAL** |
| manage_mcp | list / **tools**(读缓存快照) | LOW |
| manage_mcp | refresh/enable/disable/register/remove/**setPolicy**/**call** | **HIGH(跟随全局档,无单独强制审批)** |
| mcp__* 挂载工具 | — | HIGH |
| mcp__*__photos_manage | trash_list | LOW(只读查看回收站) |
| mcp__*__photos_manage | move/copy/rename/delete(→回收站)/trash_restore/album_create | HIGH(可恢复写操作) |
| mcp__*__photos_manage | **trash_purge** | **CRITICAL**(彻底删除手机照片,仅回收站内可用;无人值守直接拒绝) |
| manage_skill | 全部动作 | LOW |
| manage_knowledge | list/stats | LOW |
| manage_knowledge | index/reindex | HIGH |
| manage_knowledge | **remove** | **CRITICAL**(删文档+分块/向量) |
| manage_automation | list/executions | LOW |
| manage_automation | create/toggle/run | HIGH(prompt 进无人值守通道) |
| manage_automation | **remove** | **CRITICAL** |
| run_command | — | HIGH(本机终端;不做命令白名单,防线=审批卡完整命令) |

**易错点**:
- `manage_mcp` 的 register/remove 是 **HIGH 不是 CRITICAL**(2026-09-11 用户明确要求:跟随全局权限档,不做单独审批业务)。曾被定成 CRITICAL 导致 FULL 档下注册仍弹窗,已修正
- MCP action 别名 `create→register`/`delete→remove`(`normalizeMcpAction`):**分类器/执行层/审批明细三处必须共用**,否则判定漂移
- **别名归一化已扩展到全部 manage_* 工具(2026-09-18 复盘数据驱动)**:`normalizeDatasourceAction`(add→create/delete→remove/tables→schema)、`normalizeServiceAction`(add→register/delete→remove/pause→disable/resume→enable)——实测模型写 add/delete 被拒;**同样三处共用**;`manage_mcp` 的归一化输出小写(setpolicy 用小写 case);`normalizeMcpTransport`(http→STREAMABLE/local→STDIO 等,实测模型写 transport="http");manage_workspace 的 download/save/fetch→import
- **参数方言兼容(2026-09-18)**:manage_workspace 的 path 接受 `filename`/`file` 别名(实测 12 次失败全是模型写 filename)——harness 层吸收模型直觉,别让用户为参数名教学买单;manage_file 管理动作的 id 接受 target 别名
- **参数理解统一(2026-09-20,架构设计 §5.1)**:RiskClassifier 与执行层共用 `workspacePathOf`(path→filename→file 别名序)与 `normalizeWorkspaceAction`(download/save/fetch→import)——审批检查的目标必须与真正执行的目标一致(修复过:write+filename 写区外被判区内 LOW 的分歧);分类器解析已弃用字符串 indexOf(改 ObjectMapper)
- **结果状态语义(2026-09-20)**:步骤状态除 completed/failed/declined 外新增 `unknown`(结果未知:调用已发出但中断,远端可能已生效——MCP 调用中断且未声明 readOnlyHint 时不自动重放,返回此状态)与 `partial`(部分成功:fetch_media 有失败项);chat_run 终态含任一 → partial
- **循环检测结果感知(2026-09-20,§9.3)**:`LoopDetector` 同参数且**结果也不变**才累计(结果指纹=长度+头尾采样哈希),结果变化重置——正常轮询不被误拦;替换了旧的纯计数 Map
- **MCP 重试策略(2026-09-20,§9.2;验收 F2/F7 修正)**:本地参数解析失败=可纠正错误(请求不发出,不是 unknown);连接建立失败(调用未发出)→ 重连一次(安全);调用中断(结果未知)→ 默认不重放,**仅** `nora.agent.mcp.trust-readonly-hints=true`(默认 false)且工具声明 readOnlyHint=true 时才重试——远端注解单独不足以开启重放
- **循环指纹规范化(2026-09-20,F4/F5+R2/R3 终版)**:指纹=标准工具名(read_file→manage_file)+`canonicalArgs(name, args)` **按工具语义分层**——挂载 MCP 工具(mcp__*)参数**全部保留**(description/filename/action 是远端业务字段);内置工具剔除 description、filename/file→path、动作方言归一(path 空串与执行层同语义回退 filename);**递归**排序嵌套对象(数组顺序保留)。结果指纹=全内容 SHA-256。换描述(内置)/嵌套换序不能绕过;MCP 业务字段改动不误拦;不同动作不互相误判
- **fetch_media 状态派生(验收 F3)**:可用=成功+跳过;全部失败=failed;有可用+有失败=partial——状态与文案同一份统计
- **截断续读提示(验收 F6)**:完整原始路径+真实下一行号(按截断点换行数),照抄可续读
- **文件工具路径路由(2026-09-18 夜)**:read_file 的 read 接受**路径/文件名**(非数字 target 自动路由:`@center/名`/纯文件名先查文件中心,其余转工作区读)——模型「读文件」心智模型合一,不再需要记住「哪个域用哪种寻址」;import 的 path 是目录时自动补文件名;日记空态给可操作出路
- **环境摘要注入(2026-09-18)**:系统提示注入数据源名单+纳管源名单(TTL 90s 缓存)——消灭「先 list→schema→再查」的探索调用(execute_sql 失败大头是猜库名/容器名)
- **MCP 延迟加载(2026-09-18 P2-9)**:服务器级 `tool_policy`(V17 迁移)——eager=工具直接挂载;lazy=不挂载,agent 经 `manage_mcp action=tools/call` 使用。lazy 服务器工具不进 tools spec(省每轮上下文);设置页卡片可切换;`[引用MCP服务器]` 注入按策略区分文案;**lazy schema 获取(2026-09-20,§6)**:`action=tools target=X tool=<工具名>` 返回该工具完整 inputSchema,调用前先读 schema 再 call
- 区外**读**是 LOW(只读无破坏);只有写/删才升级
- `manage_skill` 全 LOW(纯数据操作)
- **edit 是精确替换**(old_string 唯一匹配才执行):与 write 同分级;新增写类动作时注意 RiskClassifier 的 `knownAction` 白名单要同步
- 新工具的四同步清单:RiskClassifier 分级 / buildApprovalRequest 明细 / 权威文档表格 / 本 skill 表格

## 敏感数据三层脱敏(register MCP / 数据源时)

| 层 | 行为 |
|---|---|
| 日志 | headers/env 值、password/token → `***`(`scrubArgsForLog`) |
| 审批卡/步骤 | 只显示**键名** + `(值已隐藏)` |
| DB 回读 API | 只留前 6 字符(`maskValues`) |

对话记录本身不脱敏(用户贴的原文保留)——agent 应提醒用户轮换贴过的 token。

## GitHub OAuth 一键登录(2026-09-11 新增)

- 设备码流程(与 gh CLI 同款):`/api/mcp/oauth/github/{status,client-id,start,poll}`;前端 MCP 页「GitHub 登录」按钮
- 完成后自动配置名为 `github` 的服务器 = 官方远程端点 `https://api.githubcopilot.com/mcp/`(44 工具含 get_me)
- client_id:GitHub 不支持 DCR,需一次性配置(建 OAuth App 勾 Enable Device Flow);存 app_setting,PUT/DELETE `/client-id` 管理
- **npm 包 `@modelcontextprotocol/server-github` 已弃用**——GitHub MCP 统一远程端点;需要 token 手动注册时用 STREAMABLE + `Authorization: Bearer` 头

## 审批卡明细(actionType)

`sql_write` / `container_control` / `datasource_manage` / `service_manage` / `mcp_manage`(STDIO 显示完整命令行) / `workspace_file` / `terminal_command`(run_command,命令原文完整展示) / `mcp_tool`

新增工具时:①RiskClassifier 分级 ②buildApprovalRequest 明细 ③本文件+权威文档+测试断言,四处同步。

## 改风险分级的 checklist

1. `RiskClassifier.classify`(或 `classifyWorkspace`)
2. `ChatOrchestrationServiceTest.riskClassifierTiers*` 测试断言
3. 权威文档 `nora-api/docs/agent-permission-and-tools-design.md` 表格
4. 本 skill 速查表
5. 工具 description 里若写了"需批准"字样要同步
6. 前端文案(`nora-web/src/lib/api/chatApi.ts` 档位描述、页面提示语)
