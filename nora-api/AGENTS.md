# Nora 后端维护

先遵守 [根项目规范](../AGENTS.md)，本文件补充后端职责和运行不变量。

当前结构以 [README](README.md) 为准：单 Spring Boot 应用，默认 18080；Java 21 / Maven / LangChain4j。
开发、测试和生产使用同一源码和领域服务，禁止重建内部 HTTP 客户端、网关或第二套 Agent 入口。

## 领域服务与装配

- 业务分包 `com.nora.{agent,file,rag,datasource,env,automation,notification}`；Controller 和工具直接共用 service。
- Agent 编排、工具、上下文与模型服务由 Spring 构造器统一注入；不在业务调用中手工重建这些服务，不保留测试专用重载或启动后的 setter 注入。普通值对象和 SDK 请求对象正常构造，不强行注册为 Bean。

## 会话、流与终态

- `ChatRunService` 唯一管理会话占用、排队和取消；`ChatRunFinalizer` 的持久化终态先于完成通知。提交时暂时清除线程中断、保存后恢复，取消不能丢失终态。保存失败时恢复落库，不自动重放工具。
- 命令终态由 `CommandExecutionStore` 先写工作区 `.runtime/command-finalizations`，落库成功才发布完成；保存失败只重试保存，启动恢复日志不重放命令。部署与备份须保留待提交日志。
- HTTP SSE 断开不代表 Run 取消；重连使用 `TurnStreamRegistry.subscribeGated`，不能让新事件越过历史回放。
- 提问期间插入当前轮次的请求，应唤醒 `ask_user` 并交回原编排处理完整输入和引用；不能伪造回答、等待旧问题超时或自动批准审批。普通排队不唤醒提问，工具终态负责清除旧问题票据与快照。
- 取消受理后等工作线程退出再释放会话占用；后台任务使用相同规则。自动任务读取 Run 结果，不解析自己系统的 SSE。

## 工具、权限与模型契约

- SQL/Redis 支持读写；目标必须明确且唯一。结果未知时标记 unknown，先核实，不自动重发。
- 高风险工具沿用统一权限门。无人值守拒绝 CRITICAL；ASSIST 诊断也拒绝 HIGH。审批绑定 sessionId/stepId，120 秒后拒绝。
- Playwright 默认无头模式；浏览器的操作、接管和验收共享同一所有权与任务状态。
- 外部模型使用现有 LangChain4j 模型。全部内置工具由 `AnnotatedTools` 从实际 `@Tool` 方法、`@P` 或请求 record 生成契约并交给 SDK 执行；复杂参数仅在注册时展平为顶层 schema。类型、必填和未知字段在绑定入口校验，动作及业务条件由工具方法校验；MCP 沿用远端 schema。
- 模型历史保留 SDK `ChatMessage`、内容、工具请求和声明；不要增加并行 JSON 消息模型或在每轮重建契约。会话、步骤及输出回调用 SDK 调用上下文传递，不混入用户可见参数。
- 工具声明分基础能力与按需专用能力：`load_tools` 仅提供契约，执行仍经过统一权限门。请求与上下文预算都使用 `ChatToolsSpec.forMessages`；不要再默认注入全部内置工具，也不要新增跨会话的加载状态缓存。环境工具统一为 `manage_service`。
- SDK 调用须保留原始类型结果的 truncated/unknown 信息，并传播取消和执行异常；不能用字符串结果丢失终态。步骤/审批的参数投影只用于展示，统一权限门在实际执行之前。

## 数据一致性与可观测性

- 数据库沿用七个 schema 和独立 Flyway 历史；遵守根规范的迁移约束，新迁移放到 `src/main/resources/db/migration/{domain}`。
- 文件生命周期与知识库状态在同一事务中提交；磁盘删除在提交后。旧 outbox 仅供升级恢复，不产生新记录。
- RAG 先构建后发布，嵌入在事务外；失败保留可重建的正文和错误原因。范围限制同时应用向量/关键词通道，降级必须可见。
- 异步执行使用 `TraceContext.wrap` 传播日志上下文；不记录原始密钥。日志 `nora.log` / `nora-text.log`，LLM SSE 时间线单独记录。

## 验证

在本目录执行 `mvn verify`（编译、冒烟、Spotless）；在仓库根目录执行 `python scripts/acceptance.py standards` 对真实后端检查错误语义、数据源读写、文件与调度。套件前置条件与专项选择见 [验收入口](../docs/dev/acceptance.md)。
涉及会话、审批、取消、压缩或重启时，补做相应真实服务验收，不能以 mock 冒烟代替。
构建前停止运行同一 jar 的本项目进程；在仓库根目录执行 `./nora.sh build` 可完成停止、verify、启动和探活，Windows 原生入口为 `powershell -File scripts/backend.ps1 -Action build`。

权限与工具规则见 [agent-permission-and-tools-design.md](docs/agent-permission-and-tools-design.md)。早期 architecture-v2 / implementation-spec 是历史设计，不作为当前部署依据。
