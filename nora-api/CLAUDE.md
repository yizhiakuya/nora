# nora-api — 后端微服务

## 服务与端口

gateway(8080) → file(8081) / rag(8082) / agent(8083) / datasource(8084) / env(8085) / automation

## agent-service 关键链路

- **对话入口** `AgentController POST /api/chat/sessions/{id}/messages`,body `{content, model, reasoningLevel, permissionMode}`;SSE 事件:`step`/`delta`/`reasoning_delta`/`sources`/`approval_required`/`done`/`error`
- **注入可见性(dsh 模式)**:每轮在检索步骤后下发 `type=context` 步骤——`s-context-memory`(form=instructions,工作区引导 SOUL/AGENTS/USER/MEMORY 逐文件元数据+注入正文 + 日记清单)、`s-context-skills`(form=catalog,启用技能条目);`ChatStepDto.context` 携带 `{form,kind,files|entries,dailyNotes}`,form 是 producer 声明的信息形态,前端按 form 渲染、未知 form 降级通用展示。文件 `bytes` 为实际注入的 **UTF-8 字节数**(非字符数),`content` 是模型实际读到的注入正文(前端文件行点击展开可见)。注入内容仍进系统提示(`systemPromptWith`),context 步骤只是让注入对用户可见
- **断线重连(后台持续运行)**:SSE 断开不影响编排线程;每轮注册 `TurnStreamRegistry`(内存事件缓冲,step/delta/sources/approval 全量,上限 8000),事件双写(直发+入缓冲)。`GET /sessions/{id}/turn/live` 探测进行中轮次(含缓冲事件数/最后事件),`GET /sessions/{id}/turn/stream`(SSE)先回放缓冲再实时续推直至 done/error,无轮次发 `idle` 即关。前端卸载**不 abort**(切页后端照跑),挂载时探测 live 轮接流恢复;「停止生成」仍走 cancel(真中断)
- **审批** `POST /api/chat/approvals/{token}?sessionId={id}` body `{approved}`;服务端内存保存一次性 token,120 秒超时自动拒绝;模型文字同意不算批准
- **权限三档** `PermissionMode`:ASK(每次询问) / ASSIST(只读自动,写 SQL/容器控制询问) / FULL(全自动);RiskClassifier 第三档 CRITICAL(删数据源、注册/删纳管源)任何档位都强制审批
- **高风险工具**:`execute_write_sql` 经 RiskClassifier → datasource `POST /api/datasources/{id}/execute` → WriteGuard 只允许单条写语句;`manage_container` 调 env-service;`manage_datasource`(list/create/test/schema/remove)与 `manage_service`(纳管源 register/enable/disable/remove/list)走各自管理端点,create 后自动 test,密码不落对话记录;`read_file` 读工作台文件(file-service,先 list 拿 id 再读)
- **MCP 管理**:agent 侧 `manage_mcp`(list/refresh/enable/disable/register/remove)与设置页 `/api/mcp/servers` 共用 `McpServerService`——register 后自动 refresh(测试连接+拉工具清单,失败不回滚注册),list 视图 headers 已脱敏,register 的 headers 值只传服务层(日志经 `scrubArgsForLog` 脱敏、步骤只存 target、不落库);风险分级:list=LOW、refresh/enable/disable=HIGH、register/remove=CRITICAL(无人值守通道拒 register/remove)
- **无人值守通道**:`/api/chat/agent/run`(automation)无会话=无审批,CRITICAL 工具在该通道直接 declined;HIGH 按设计放行
- **编排** `ChatOrchestrationService.chat()`:RAG 检索 → 每轮 `streamTurn` 真流式(JDK HttpClient 逐行读上游 SSE,tool_calls 增量累积到流结束再执行)→ `streamFinalAnswer` 兜底
- **usage**:上游最后 chunk(空 choices)带真实 token,跨工具轮累加后 `done.usage` 下发
- **持久化**:会话/消息/step 落 schema_agent;reasoning 聚合后一次性保存(`s-reasoning-{round}`)
- **工作区/记忆(文件系统一体化,对齐 OpenClaw/Hermes)**:agent 的目录(默认 `D:/claude/Nora/agent-workspace`,可配 `nora.agent.workspace`)既是**默认 cwd**也是记忆载体。引导注入按 **Hermes 三层提示词结构**(stable 身份 → context 约定 → volatile 快照):`SOUL.md`(可演化人格:agent 自己改它就改变下轮行为)/`AGENTS.md`(使用约定,从经验中补充)/`USER.md`(偏好)/`MEMORY.md`(耐久事实)——每轮自动注入,逐文件 6k 字+总量 16k 字双预算截断;`memory/YYYY-MM-DD.md` 日记只列清单不注入,按需 `read`。**SYSTEM_PROMPT 只留协议层**(引用标记/行动而非空谈/自演化声明),人格与任务习惯全部下放到可演化文件(改文件即进化,不改代码)。「记住…」= 落盘(无隐藏状态)。**工作区是默认目录而非硬沙箱**(OpenClaw 语义):相对路径=区内,**绝对路径=整机**(agent 可读/写项目文件);风险分级 `manage_workspace`——区内写=LOW 自动、区外写=HIGH(ASSIST 询问)、区外删=CRITICAL(任何档位确认),系统目录(Windows/Program Files/盘根)写删硬拒;管理 API `/api/workspace`(GET stats/files/file,PUT file,DELETE file——**仅限区内**,前端管理通道不开放整机)。前端:文件页把工作区渲染为**普通文件夹**「Agent 工作区」(点击进入/面包屑导航/文件编辑器,与真实文件系统一体),非独立页面
- **指令型技能**:`agent_skill` 表(名称/描述/分类/正文),启用技能的**目录**(名称+描述)注入系统提示,正文由 `manage_skill action=read` 按需拉取(渐进披露,不占每轮预算);CRUD API `/api/skills`(列表不返回正文,详情才返回);agent 可在对话中 create/update 沉淀技能(闭环自管)

## 模型/推理注入规则

- 模型解析优先级:请求级模型名 > provider store(设置中心) > 静态 `nora.llm.*` 兜底;不要让环境变量短路 store
- 思考等级合并:请求级 > 设置页 per-model 默认 > auto;白名单 `reasoningLevels` 约束
- 注入:gpt-5/o 默认 medium;claude 必须带 reasoning_effort;glm 用 `thinking:{type}`;qwen 用 `enable_thinking`;none→minimal/disabled
- **Responses 协议必须带 `reasoning.summary=auto`**,否则 muse 等模型的推理内容(encrypted_content)全被吞

## 已知坑

- **时区全局约定**:`nora.timezone`(默认 Asia/Shanghai)统一三处——JVM 默认时区(nora-common `TimeZoneConfig` 静态块生效)、Jackson 序列化、DB 会话(`ALTER DATABASE nora SET timezone`,已固定)。DB 时间列一律 `timestamp without time zone` 存本地挂钟时间,Java 读取必须用 `LocalDateTime`(用 `OffsetDateTime` 读会被 JDBC 贴错 UTC 标签,前端 +8h);前端解析 `created_at` 走 `toHm()`(agentApi)手动拆解,不用 `new Date()`
- **SSE 上游用 JDK HttpClient 流式读取**,不要用 RestClient `.body(byte[].class)`(伪流式),其字符串转换器还会把 text/event-stream 按 ISO-8859-1 弄乱中文
- 测试 mock:`ModelProviderServiceTest` 用 Strict stubs,参数不匹配直接报 PotentialStubbingProblem
- 工具输出 30K/10K 截断;循环熔断(同参数 3 次阻断)
- execute_sql guardrail:单条 SELECT/SHOW/EXPLAIN
- **取消语义**:cancel/超时中断编排线程后,JDK HttpClient 阻塞读抛 `IOException(InterruptedException)` 进 streamUpstream 的 `failed` 结果——编排层到处检查 `Thread.currentThread().isInterrupted()`/`isInterruption(cause)` 短路返回 `failedFuture(CancellationException)`,**任何空响应重试/最终回答兜底都必须排除中断**,否则取消变成多烧一整轮 token 且取消轮被当正常完成落库;控制器收尾的 `answer` StringBuilder 靠 `delta()` append 维持半截内容
- **`.env.local` 启动自动加载**:nora-common `DotenvEnvironmentPostProcessor`(spring.factories 注册)以最低优先级加载 `nora-api/.env.local`,真实环境变量优先;key 找不到会走默认空值 → rag 报 "embedding not configured"、外网调用直连超时(需 `NORA_PROXY_ENABLED=true`)——排障先查 dotenv 日志与 key/代理

## LLM 通道

agent-service → sub2api 中转 `http://192.168.0.109:28765/v1`(内网直连);provider 存 model_provider 表(api_key 明文,key 不回传,mask 后展示);模型发现:测试连通时 GET /models

## 日志/排障约定

- **全链路 traceId**:入口 `TraceIdFilter`(nora-common 自动装配)读/生成 `X-Nora-Trace-Id` 并写 MDC,响应头回写;服务间 RestClient 出口自动注入;前端每页面会话生成 browserTraceId 随全部请求携带——前后端日志按同一 ID 串联。gateway(WebFlux)走 GatewayTraceFilter 同语义
- **JSON 日志**:每个服务双文件输出到 `LOG_PATH`(缺省 D:/claude/Nora/logs)——`{service}.log` 是 JSON 行(traceId/service 为独立字段,`jq 'select(.traceId=="…")'` 直接检索),`{service}-text.log` 是人读文本;共享基座 `common/nora-common/src/main/resources/logging/nora-logbase.xml`,各服务 logback-spring.xml 只做差异化
- **agent 对话轮次**:sessionId/turnId 进 MDC,一轮对话从编排到落库的全部日志按会话串联;上游 LLM SSE 时间线单独落 `agent-service-sse.log`;SSE 事件(step/approval/done/error)在主日志有逐条时间线
- **前端错误上报**:errorReporter 全局兜底 → `POST /api/log/frontend`(FrontendLogController),入库即主日志;500 错误消息会拼 `[trace=…]`,报障按 ID 直查后端
- **异步线程必须用 `TraceContext.wrap()`** 包装 Runnable/Callable,否则 MDC 丢失、日志断链

## 测试

`mvn -pl services/agent-service test`(61 用例)+ `mvn -pl common/nora-common test`(8 用例)+ `mvn -pl services/rag-service test`(30 用例)

## 文档

- `docs/agent-implementation-spec.md` — 执行协议/审批协议规格
- `docs/harness-tool-calling-research-2026-09-05.md` — harness 调研
