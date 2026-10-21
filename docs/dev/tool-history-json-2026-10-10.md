# 工具历史参数 JSON 修复

## 根因与影响链路

线上 `sess-1791600024701` 的 2026-10-10 12:36 轮次在 HTTP 请求发出前失败。数据库留存记录中有 6 条 `sh` 工具的 `input.rawArgs` 无法解析：工具参数先经过结构化脱敏得到合法 JSON，`ToolStepEmitter.emitStep` 又通过 `ChatToolExecutor.redactValue` 脱敏整个步骤；`rawArgs` 被按普通字符串处理，文本凭据正则在 JSON 转义引号中插入未转义的 `[REDACTED]` 引号，破坏参数 JSON。

下一轮 `ChatContextAssembler.appendHistoryMessage` 无条件重建这些参数；LangChain4j Anthropic 的 `tool_use.input` 使用 `@JsonRawValue`，损坏参数污染整个请求，`UpstreamLlmClient.prepareRequest` 解析 SDK 请求时失败。`JsonProcessingException` 又是 `IOException` 子类，被归类成网络中断而重试三次。

## 最小设计

- 在已有 `scrubCredentials` 的 JSON 字符串分支中统一处理 `arguments` 和 `rawArgs`，先解析内部对象、逐字段脱敏、再序列化；普通输出文本继续走文本脱敏，复用原有能力。
- 历史重建边界严格解析参数对象并拒绝尾随内容。合法参数规范化后按原调用/结果配对重放；损坏参数对应的既有工具结果保留为明确的历史文本，不编造参数、不生成孤儿工具结果、不重新执行已发生的操作。其余同轮合法工具仍可正常重放。
- 请求尚未送达 HTTP 客户端时发生的本地 JSON 解析失败不重试；实际网络/流式中断沿用现有重试逻辑。
- 不修改用户会话记录或凭据。当前会话在排查期间已被软删，使用只读留存取证，并在独立验收会话验证历史回放。

## 影响范围与验证

共享脱敏边界的调用方包括工具步骤、审批/问题明细及工具参数展示；历史装配同时服务普通聊天、压缩与续接。前端与数据库 schema 均无需改动。

冒烟覆盖嵌套 JSON 参数中的引号、反斜杠、凭据字段与正常命令；历史回放覆盖同轮合法/损坏参数、非对象与尾随内容，并使用 LangChain4j 实际请求序列化。通过真实 Agent 服务执行安全命令、保存工具记录、发起下一轮真实模型请求，及留存历史的独立会话回放验收。发布仅更新 Agent，空闲检查、备份和配置/历史校验沿用现有流程。

## 本地验收结果

- 修复前用双引号 Authorization 命令复现同样的 `Unexpected character '['`；修复后嵌套参数可解析且凭据仍脱敏。
- Agent 165 项、common 9 项冒烟通过，生产 JAR 构建成功。
- 真实 nora-a / Anthropic 模型 E2E 共 13 项通过：安全命令实际执行并保存、下一轮结构化历史回放、6 条线上损坏记录的历史文本与全部结果保留、真实连接断开后的自动重试及模型回答。
- 本地报告：`logs/tool-history-e2e-1791607969996590200.json`。请求内容和私有留存不纳入版本控制。

## 线上发布与验收

- 已发布 `tool-history-20261010-125216`，只更新 Agent；运行 JAR 的 SHA256 与本地构建清单一致，Web 的容器 ID、镜像与启动时间未变。
- 备份位于 `/opt/nora/backups/tool-history-20261010-125216`，包含数据库、部署配置；旧 Agent 镜像保留为 `before-tool-history-20261010-125216`。
- 用户确认停止 `sess-1791607146601` 后部署。该会话的运行记录仍非终态，但停止接口与实时接口显示没有在途任务；仅对明确获准停止的 `62081be7` 标记取消请求，重启后由既有恢复流程收尾为 `cancelled / process_restarted`，保留已保存过程。
- 线上使用现有 `sub2api` 配置的真实模型，13 项 E2E 全部通过。报告：`logs/tool-history-release/tool-history-e2e-1791608580957676757.json`。临时验收 provider 按现有 API 软删，原 provider、设置和 MCP 配置摘要与发布前一致；历史数量未减少。
- Agent、Web、Postgres 最终均为 healthy，验收记录见 `logs/tool-history-release/production-verification.json`。

发布前另发现根分区写满，Postgres 因 `No space left on device` 循环恢复。清理超过 24 小时的闲置构建缓存与未使用的无标签镜像后，数据库恢复连接；最终根分区约余 1.1 GiB。本次未进行全盘容量治理，空间余量仍需要后续处理。
