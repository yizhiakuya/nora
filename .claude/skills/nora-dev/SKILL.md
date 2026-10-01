# Nora 开发环境管理(nora-dev)

稳定可复现的后端开发环境:一条命令启动全部服务、代码更新后精准重启、无 jar 锁冲突。

## 前置依赖(一次性)

Docker 容器 `nora-postgres` / `nora-nacos` / `nora-redis` 必须在运行:

```bash
docker start nora-postgres nora-nacos nora-redis
```

检查:`docker ps --format "{{.Names}} {{.Status}}"`。

## 服务与端口

| 服务 | 端口 | 模块路径 |
|---|---|---|
| gateway | 8080 | services/gateway-service |
| file | 8081 | services/file-service |
| rag | 8082 | services/rag-service |
| agent | 8083 | services/agent-service |
| datasource | 8084 | services/datasource-service |
| env | 8085 | services/env-service |
| automation | 8086 | services/automation-service |

前端:内置浏览器预览启动(端口 3001,`.claude/launch.json` name=nora-web);不要裸 `pnpm dev` 后台进程——启动后截图验证工作台正常、无控制台报错。

## 命令

### 启动全部(基础设施已在跑时)

```bash
cd nora-api
for svc in gateway file rag agent datasource env; do
  (java -jar services/${svc}-service/target/${svc}-service-0.1.0-SNAPSHOT.jar \
    > 仓库根/${svc}-service.stdout.log \
    2> 仓库根/${svc}-service.stderr.log &)
done
```

### 查状态

```bash
for p in 8080 8081 8082 8083 8084 8085; do printf "%s:" $p; netstat -ano | grep ":$p " | grep -c LISTENING; done
for p in 8080 8083 8085; do printf "%s health: " $p; curl -sS --noproxy '*' -o /dev/null -w "%{http_code}\n" "http://localhost:$p/actuator/health" --max-time 5; done
```

### 停止全部 / 单个

```bash
# 全部(按端口找 PID,比记 PID 可靠)
for p in 8080 8081 8082 8083 8084 8085; do
  pid=$(netstat -ano | grep ":$p " | grep LISTENING | head -1 | awk '{print $NF}')
  [ -n "$pid" ] && powershell -Command "Stop-Process -Id $pid -Force"
done

# 单个服务(例:agent)
pid=$(netstat -ano | grep ":8083 " | grep LISTENING | head -1 | awk '{print $NF}')
[ -n "$pid" ] && powershell -Command "Stop-Process -Id $pid -Force"
```

### 重启单个服务(改代码后的标准流程)

```bash
cd nora-api
# 1. 停(见上,必须先停:jar 被运行中进程锁定,package 会静默产出旧包或失败)
# 2. 打包
mvn -q -pl services/agent-service clean package -DskipTests
# 3. 起
(java -jar services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar \
  > 仓库根/agent-service.stdout.log 2> 仓库根/agent-service.stderr.log &)
# 4. 验证
sleep 12 && curl -sS --noproxy '*' -o /dev/null -w "%{http_code}\n" http://localhost:8083/actuator/health
```

## 已踩过的坑(务必遵守)

1. **改了 `common/nora-common` 必须先 install 再打包服务**,否则服务打包用的还是 m2 里的旧 common:
   ```bash
   mvn -q -pl common/nora-common clean install -DskipTests
   mvn -q -pl services/agent-service clean package -DskipTests
   ```
2. **api 模块源码比 m2 新时,单独 `-pl services/<svc>` 构建会失败**(如 DbTable 三参构造):依据报错模块先 install 对应 api 模块,再编服务。
3. **不用 `clean` 有 stale 风险**:曾出现 package BUILD SUCCESS 但 jar 时间戳不变(内嵌旧 common)。涉及 common 变更时一律 `clean package`。
4. **jar 锁**:Windows 下运行中的 java 进程锁住 jar 文件,先 Stop-Process 再 package(仓库有 jar_lock_guard 钩子会拦截)。
5. **服务间注册经 Nacos**:全部重启后等 ~30s 再做端到端测试(网关路由刷新)。
6. **curl 一律加 `--noproxy '*'`**:本机系统代理会把 localhost 请求吞掉返回 502,误判服务挂了。
7. **验证包内容**:怀疑包旧时,直接查 fat jar 内嵌 class:
   ```bash
   unzip -p <jar> BOOT-INF/lib/nora-common-0.1.0-SNAPSHOT.jar > /tmp/nc.jar
   unzip -p /tmp/nc.jar com/nora/common/exception/GlobalExceptionHandler.class | grep -c <特征串>
   ```
8. **日志**:agent-service 配置了 logback(`logs/agent-service.log`,DEBUG 级,含 LLM 报文/上游错误体/完整堆栈)。stdout/stderr 日志在 `仓库根/<svc>-service.*.log`。
9. **验证服务真活着的标准**:端口 LISTENING + `/actuator/health` 200,两者都要。
10. **`.env.local` 启动自动加载**:nora-common `DotenvEnvironmentPostProcessor`(spring.factories)以最低优先级加载 `nora-api/.env.local`(真实环境变量优先;改动需重启服务)。RAG 报 "embedding not configured" 或 Jina 等外网调用直连超时先查:key 是否在 `.env.local`、`NORA_PROXY_ENABLED=true`(外网需走 127.0.0.1:7897 代理)。
- **Agent 工作区(文件系统记忆,对齐 OpenClaw)**:默认 `agent-workspace`(可配 `nora.agent.workspace`),agent 用 `manage_workspace` 读写——**默认 cwd 非硬沙箱**:相对=区内(写自动),绝对=整机(写 HIGH 审批/删 CRITICAL);`AGENTS/SOUL/USER/MEMORY.md` 每轮自动注入(双预算截断),`memory/` 日记按需读。设置中心「工作区」页可直接编辑;前端 API `/api/workspace`,注意 **gateway 路由是白名单式的**——新增服务前缀必须同时加 `services/gateway-service/.../application.yml` 的 route,否则 404
- **指令型技能**:`agent_skill` 表 + `/api/skills`(列表不带正文/详情带正文);启用技能目录注入系统提示,正文由 agent `manage_skill read` 按需拉取
- **权限/风险/工具设计**:改 `RiskClassifier`/`toolsSpec`/审批相关代码前,先看 skill **nora-agent-tools** 或权威文档 `nora-api/docs/agent-permission-and-tools-design.md`(权限三档 × 风险三级全表、MCP 管理跟随全局档位、脱敏边界);改后四处同步:分类器/测试断言/权威文档/skill
- **工具设计分析与路线图(2026-09-18)**:全面审计报告在 `nora-api/docs/agent-tool-design-analysis-2026-09-18.md`(76 工具真实使用数据 + 业界调研 + P0/P1/P2 路线图 + 新工具设计 checklist/反模式)。**P0/P1/P2 全部落地**:enum schema 纪律、`edit` 精确替换、`environment_status`/`search_knowledge`/`manage_knowledge`/`manage_automation` 四新工具、tools spec 缓存按 `toolsRevision` 失效;MCP 服务器级 `tool_policy`(V17 迁移,eager/lazy)——lazy 服务器工具不挂载,经 `manage_mcp action=tools/call` 按需使用(实测 github 44 工具设 lazy 后 prompt -52%);别名归一化补齐(manage_datasource/manage_service 的 add/delete 等,分类器/执行层/审批明细三处共用,与 normalizeMcpAction 同款)。**运营工具**:`scripts/tool-usage-review.sh`(工具使用复盘,7 节:零调用/高失败/高耗时/高轮次,数据源 agent_step)+ `scripts/tool-eval.sh` + `scripts/tool-eval-cases.json`(工具选择层评测,打真实链路解析 SSE,10 用例含 2 负例)。**加新工具接线五处**(与权威文档「四处同步」并集):①`ChatToolsSpec` schema(有限值必带 enum)②`ChatToolExecutor` 分发一行 + handler ③`RiskClassifier` 分级 ④`ToolStepEmitter`(parseArgs 展示字段 + defaultTitle + rawFingerprint 名单 + buildApprovalRequest 明细)⑤文档三件套(权威文档表格 / nora-agent-tools skill / 手册技能 id=7 经 `PUT /api/skills/7` 更新——**数据库 MCP 只读,改手册必须走 HTTP API**)。**排障提示**:agent-service 启动失败先查 Docker 容器(nora-postgres/nacos/redis 可能整机重启后是 Exited 状态,`docker start` 即可);Windows 下 bash 脚本调 python 必须 `python -X utf8`(GBK stdout 会炸中文)
- **codebase-memory 索引与 env 目录**:索引器内置 skip-list 按目录名匹配 Python 惯例目录(`env`/`venv`/`__pycache__`/`htmlcov` 等),会误伤 Java 包路径 `com/nora/env`——仓库根 `.cbmignore` 已用取反规则 `!**/env/` 修复(2026-09-16)。**以后新增 `env` 名目录无需再处理;若发现其他目录被排除,先查 `index_status` 的 `not_indexed`,再用 `.cbmignore` 取反**。索引器还要求仓库根在允许名单内(`allow-root`),排除清单在二进制里,排查思路:索引日志(索引器缓存目录下的 `logs/`) + 项目库 `D-claude-Nora.db` 的 `index_coverage` 表。
- **复杂度审计(2026-09-16)**:报告在 `nora-api/docs/complexity-audit-2026-09-16.md`,拆分方案在 `chat-orchestration-refactor-plan-2026-09-16.md`。要点:ChatOrchestrationService 4197 行是唯一大热点(executeTool 562 行/toolsSpec 456 行),拆分按文档四步走;图谱对 TSX 的「死代码」多为 JSX 引用误报(handleSave/copyId 等勿删)。
- **ChatOrchestrationService 已拆分(2026-09-17,四步全完成)**:facade 4178 → 1502 行(-64%),拆出 8 类 + 3 共享类型,均在 `com.nora.agent.service` 包:
  - `ChatToolsSpec`(490 行):11 工具 JSON Schema + MCP 动态挂载装配,`build()`;
  - `ChatToolExecutor`(936 行):executeTool 11 路分发 + bounded/guardSql/guardService + URL 导入 + resolveSkill/skillNameList,`ToolOutcome` 在这里;
  - `UpstreamLlmClient`(679 行):两套协议 SSE + baseBody/applyReasoningRequest + friendlyUpstreamError,`TokenSink` 在这里;
  - `ChatContextAssembler`(579 行):buildMessages/历史 wire 重建/compactForRound/recycleOldImages/messageTokens/toolsOverheadTokens/systemPromptWith,`SystemPromptResult` 在这里;压缩统计走 `lastRecycledImages()`/`estimateTokensFreed()` getter;
  - `ModelResolver`(84 行):`resolve(...)` 解析链(请求 > store > 静态兜底);`ModelCapabilityRegistry`(107 行):vision/effort 降级记忆(含 `isVisionUnsupported`/`isGenericUpstream400`/`isReasoningEffortUnsupported` 静态判定);
  - `Texts`(43 行):abbreviate/firstNonNull/isInterruption;共享类型独立文件:`ResolvedLlm`(含 `withLevel`)/`StreamTurnResult`/`WireMessage`。
  - **改代码指引**:加工具动 ChatToolsSpec+ChatToolExecutor;改流式/降级动 UpstreamLlmClient+ModelCapabilityRegistry;改历史装配/压缩动 ChatContextAssembler;facade 只留主循环/配置。测试反射目标已同步(toolExecutorOf/contextAssemblerOf/stepEmitterOf 辅助取 facade 字段)。
- **执行层再拆(2026-09-17 晚)**:`ToolStepEmitter`(603 行,emitToolStep 全生命周期+审批构建+args 脱敏/重放,`ParsedArgs` 在这里);`ChatToolExecutor.executeTool` 拆为 71 行分发器 + 11 个 `exec*` handler(每工具一个方法,加工具=加一个 handler+分发一行+ChatToolsSpec 一段);facade 累计 4178 → 946 行(-77%)。
- **控制层与服务层拆分(2026-09-17,审计建议 #2/#3 完成)**:`AgentController` 760 → 426 行(只留端点/执行器管理),轮次执行引擎在 **ChatTurnRunner**(394 行:编排调用+事件双写+落库+取消收尾+标题生成+SSE 发送+5 个载荷 record);`McpServerService` 863 → 593 行(注册表 CRUD+工具调用),连接池/进程树在 **McpClientPool**(243 行,clientFor/evict/杀树/shutdown),命令解析在 **McpCommandResolver**(91 行,resolveCommand);前端 `AgentThoughtBlock` 651 → 235 行,工具行在 **ToolStepRow.tsx**(280 行)、注入行在 **ContextStepRow.tsx**(140 行)。
- **P1 安全/真实性修复(2026-09-20,按 `docs/dev/PROJECT-ANALYSIS-2026-09-19.md`)**:
  - **只读 SQL 硬边界**:`JdbcConnections.open(params, readOnly=true)` 走数据库级只读(实测 PG JDBC **必须 `readOnly=true + readOnlyMode=always` 组合**,单 readOnly 只是客户端提示不拦服务端;MySQL 靠 `readOnlyPropagatesToServer` + `setReadOnly`),`executeReadOnly` 已接入——「EXPLAIN ANALYZE 包裹写 CTE」这类语句解析识别不出的绕过由数据库直接拒绝(SQLState 25006 → 400 + 可操作提示)。改连接参数前先跑隔离验证(见 /tmp 思路:两个 Properties 对照组打真实库)
  - **自动任务真实性**:`useAutomations.addRule` 返回 `Promise<AutomationRule|null>`(失败回滚乐观条目 + toast 人话)、`markRun` 返回 `Promise<boolean>`(乐观条目 id≥1e12 明确拒绝,不再落本地假执行分支);`syncFromBackend` 空数组也覆盖(服务端是权威);调用方(NewAutomationModal/QueryConsole/LogStream/AutomationList)已全部适配;错误文案统一 `humanizeError`(网关 JSON 兜底也能出人话)
  - **中继缓存鉴权**:relay 缓存命中不经过手机 → 命中前必须过 `cacheReadAuthorized`(新 App hello 上报令牌 sha256 落盘;旧 App 学习式授权——手机 2xx 即记住该令牌哈希,仅内存);未授权回源手机(401/503),不再裸回缓存。改动 relay.mjs 后跑 `node --check` + /tmp/pa-relay-auth-test 的四个隔离测试(stub ws 合成缓存 + 真 ws 假手机学习链路)
- **P2 可靠性修复(2026-09-20 同批)**:
  - **审批竞态**:`ApprovalService.registerWithFuture` 返回持有 future 的注册句柄,等待方直接等该 future(`awaitFuture(registered)`),不再 `await(token)` 查可被提前清空的 Map——「先 resolve 后 await」不再把批准误判为拒绝(隔离复现修复前后)
  - **SSE 游标协议**:发送端 id 改 `turnId:seq`(与解析端一致;此前裸 seq 导致浏览器重连游标解析失败全量回放);**缓冲改滚动窗口**(满员淘汰最旧而非停记,`Snapshot.gap` 显式上报缺口,前端 `onGap` 清空恢复占位);`subscribeDraining` 返回 Snapshot
  - **单会话并发闸**:`AgentController.sendMessage` 用 `activeTurns.putIfAbsent` 原子占位,重复提交 409(TURN_IN_PROGRESS);取消先于启动到达的边角用 `replace(期望值)` 检查 + 补发 done(stopped),不锁死会话;`ran` 标记避免重复终态
  - **索引事务三阶段**:`IndexingService.indexDocument` 去掉整方法 `@Transactional`,改「短事务建 processing 行 → 事务外嵌入 → 短事务写结果/独立事务标 failed」——失败标记不再随回滚消失(测试 mock 需同时内联 `txTemplate.execute` 与 `executeWithoutResult`)
  - **生命周期通知补偿**:file-service `pending_rag_sync` 表(V6 迁移)+ `RagIndexClient.notifyLifecycleAsync` 先持久化再送达,`@Scheduled` 30s 重试指数退避(封顶 10min),`FileApplication` 加 `@EnableScheduling`——「删除时 RAG 挂了,之后恢复」的通知不再永久丢失
  - **媒体令牌**:`mediaCacheApi.previewUrl`/`FileViewerModal` raw/`FileGrid`/`FileTable` 缩略图统一 `withAuthToken()`(img/video/window.open 无法带 header 走 `?token=`)
  - **自动任务触发条件**:file/error 在 UI 标 `disabled` +「即将支持」(后端调度只扫 daily/weekly,不产出永不运行的规则)
- **工程门禁(2026-09-20)**:
  - **lint 全绿**:ImageLightbox 拖拽态从 render 读 ref 改 React state(`isDragging`)
  - **路由懒加载**:`App.tsx` 11 页面 `React.lazy + Suspense`(RouteLoading 占位),主包 gzip 240KB→**97.7KB**(目标 ≤180KB);页面独立 chunk
  - **文档漂移同步**:根/后端/前端 README + AGENTS 接入表全部对齐当前行为(8 服务/Kafka 通知/令牌登录/全部域已接入);报告本身已入库
- **产品改造 M0–M5(2026-09-20 同日,按 `docs/dev/NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md`;实施记录 `docs/dev/NORA-REFACTOR-IMPLEMENTATION-LOG.md`)**:
  - **四入口导航(M1)**:助手(`/` 输入需求/继续处理/最近成果)/资料(`/files?view=files|knowledge|results`)/任务(`/tasks?view=running|schedules|history`)/设置(`?section=` 深链);旧路由全兼容(`/automations→/tasks`、`/knowledge`/`/skills`/`/mcp` 直达保留);视图组件抽为 `KnowledgeView`/`SkillsView`/`ConnectionsView` 复用
  - **结构化任务上下文(M2-01)**:`TaskContext` DTO(refs/output/origin/dataSelection);`MessageRefResolver.resolveContext` 结构化优先+旧行回退+去重,失败项**可见**;前端 `ChatResponder` 透传 context,`ChatRef` 扩展 datasource 类型
  - **跨页交接(M2-02)**:`lib/handoff.ts` 统一 URL 协议(`/chat?prompt=&refs=` JSON);文件页批量栏「交给助手」、QueryConsole 带 connectionId
  - **成果(M2-03/04)**:回答「保存为文件」(工作区 Markdown+回读校验)/「保存到知识库」并列;`RecentResults`/`SavedResultsView` 共用 execution_record
  - **偏好(M2-05)**:`/api/user-preferences` 白名单(报告语言/默认成果目录/命名习惯),`ChatContextAssembler.userPreferencesSummary` 注入每轮
  - **运行生命周期(M3)**:`chat_run` 表(V22):开始落 running(runId=turnId)、终态更新同行;`StaleRunRecovery` 启动标 interrupted;`GET /api/chat/runs` 任务页聚合;状态衔接 awaiting_approval/cancelling/partial
  - **定期任务(M4)**:`schedule` JSONB 唯一权威 + `next_run_at` 派生(V5 迁移;`ScheduleCalculator` DST 安全);`/schedule-preview` 未来 3 次;调度按 nextRunAt+10min 宽限,错过落 `missed_schedule` 不补跑;条件 UPDATE 领取计划点去重;存量规则标 `needs_config`
  - **注意**:创建 daily/weekly 规则**必须带 schedule**;`file/error` 触发类型 create 已拒绝

## 状态契约与提交边界修复(2026-09-26,按 `docs/dev/PROJECT-ANALYSIS-2026-09-26.md` F1–F5)

- **F1 文件生命周期通知原子提交**:`FileLifecycleService`(file-service)把「文件状态变更 + RAG 通知入队」放同一事务——delete/restore 用 `RETURNING id` 只对真实变化的文件入队,purge 的磁盘删除与投递都在提交后;`RagIndexClient.enqueueLifecycle` 在事务内分配版本+写 pending 行(upsert 带 `WHERE EXCLUDED.version > pending_rag_sync.version` 单调守卫,低版本晚到不覆盖高版本),`deliverPendingAsync(fileId, traceId)` 在事务外读回最新行投递(traceId 显式透传,异步线程 MDC 不继承)。**E2E 验证过**:删除→恢复→再删除收敛、RAG 停机期间删除通知保留并在恢复后送达、低版本被守卫拒绝
- **F3 统一轮次终态**:`done` 事件新增 `status` 字段(completed/partial/failed/cancelled),与 `chat_run` 落库同源同一判定(ChatTurnRunner 收尾一次算出)。判定:异常轮有可用成果(回答文本或**带 toolName 的已完成工具调用**——检索/上下文注入步骤不算)为 partial,否则 failed。**踩坑**:s-rag 步骤 type=tool 但 toolName=null,只看 type 会把零成果失败误标 partial
- **F2 自动任务结果状态化**:`ActionExecutor.execute` 返回 `ActionResult(detail, status)`,agent 动作终态来自 SSE done.status(不再 `detail.startsWith("ERROR")`/「有文字」推断);partial/cancelled/unknown 不发成功通知,unknown 文案指引去会话确认;前端 `ExecutionHistory` 六态展示(success/partial/failed/cancelled/unknown/missed_schedule)。**确定性复现工具**:`scripts/fake-sse-agent.py`(按会话 id 返回不同流型的假 agent-service,配合真 automation-service 验证终态判定)
- **F4 工具评测终态门**:`tool-eval.sh` 先验 done.status——`error` 事件或 `status != completed`(partial/failed/cancelled)都不作工具选择判定(判 ERROR),不再让「模型失败但工具选对」计 PASS
- **F5 文件索引参数闭环**:`POST /api/rag/index` 的 IndexRequest 支持 `chunkMode/chunkSize/overlap/separator/baseId`(与 /index/text 对齐;不存在的 baseId 返回 404);前端文件页「入知识库」改为配置弹窗(`IndexToKnowledgeModal`:选库/分段模式/高级参数/试切预览),确认后带参提交。**验证要点**:UI 提交后查 `knowledge_doc.chunk_config` 与 `base_id` 确认参数真实落库

## 产品语义修复(2026-09-27,按 `docs/dev/PRODUCT-DESIGN-REVIEW-2026-09-27.md` B1–B7)

- **B1 成果入口对齐实际保存行为**:新增 `saved_artifact` 表(agent-service V26)+ `/api/saved-artifacts`(POST 幂等 upsert by kind+path / GET / DELETE);对话「保存为文件/保存到知识库」成功后登记 `{kind, path, name, sessionId, messageKey}`(服务端权威,此前只有 localStorage);资料页「已保存成果」tab 改为读它(`SavedArtifactsView`:类型徽章/打开工作区深链 `?workspace=`/回到来源会话 `/chat?session=`);执行记录只留在任务页。**E2E 验证过**:UI 保存 → 服务端登记 → 成果列表 → 来源会话跳转全链路
- **B5 状态映射统一**:`nora-web/src/lib/executionStatus.ts` 的 `EXECUTION_STATUS_META`(icon/cls/label/actionHint)为唯一映射——ExecutionHistory/RecentResults/SavedResultsView 共用;首页「最近成果」改名「最近任务结果」(内容是执行记录含失败/取消)
- **B3 SQL 规则连接目标贯穿**:`CreateRequest.connectionId` → `sqlAction(sql, connectionId)` 落动作 JSON → 执行优先使用(不再默认第一项);绑定连接被删时明确指引。**踩坑**:服务间 RestClient 经 EnvelopeErrorHandler 抛的是 `DownstreamException`(不是 RestClientResponseException),catch 类型要对。前端 `useAutomations.addRule` 加第 5 参 connectionId、`QueryConsole.saveAsAutomation` 传入
- **B2 定期任务继承原始指令**:`ExecutionView.actionJson`(JOIN 规则 action 列)下发;前端「设为定期任务」用**原始 prompt/sql** + 「上次结果参考」附后,不再把结果文本当指令
- **B4 对话检索范围**:`TaskContext.retrievalScope`(baseId/docIds/sources)+ 编排层 `retrievalScopeOf` → `searchWithStatus(query, topK, scope)`;前端输入区「限定检索范围」开关(有 doc 引用时显示,一次性,发送后重置)。**验证要点**:两个库放冲突内容,限定后只召回指定文档
- **B6 交接去向明确**:`buildAssistantHandoffUrl(prompt, refs, newSession=true)` 默认 `new=1`;文件页批量栏弹「新建处理/加入当前对话」选择(`HandoffChoiceDialog`);chat 页消费 new=1 建新会话(用后清 URL 参数)
- **A2 对话产出标记(2026-09-27)**:`/api/rag/index/text` 加可选 `source`(text/chat,默认 text;非法值 400);前端 `saveTextAsync` 传 chat——知识库显示「对话产出」;详情抽屉对 chat 文档查 `saved_artifact` 显示「来源会话」链接。评测脚本不传 source,行为不变
- **B3 首页发送前加资料(2026-09-27)**:首页输入区 📎/📄 按钮(`ReferencePicker`)选引用 → chips 可删 → 发送时随 URL `refs=` 进新会话(对话页既有解析链路)。验证要点:发送后查 chat_message 落库内容含 `[引用知识库] … (doc_id=N)` 行
- **README 数字对齐(2026-09-27)**:nora-api/README 的「16 模块/106 测试」→「14 模块/272 测试」、min-score 0.45→0.51(历史报告文档保留原日期语境,不改)
- **B7 故障隔离**:`useBackendHealth` 拆双探针——`online` 探 `/api/auth/status`(gateway 自身,决定全屏替换页)、`agentOnline` 探 `/chat/health`(对话页局部横幅);agent 挂了文件/任务/数据源仍可导航

## 端到端冒烟(重启后)

```bash
# 聊天链路(走网关,验证注册与编排)
curl -sS --noproxy '*' -N -X POST "http://localhost:8083/api/chat/sessions/sess-1788853162156/messages" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary '{"content":"一句话介绍自己","model":"hy4-preview","permissionMode":"FULL"}' \
  --max-time 90 | grep -c "event:delta"
```
