# nora-api — 后端微服务

## 服务与端口

gateway(8080) → file(8081) / rag(8082) / agent(8083) / datasource(8084) / env(8085) / automation

## file-service(文件中心=统一文件系统入口,2026-09-17)

- **架构原则**:所有文件相关能力(存储/缓存/组织/流转)都归这里或经这里——根 CLAUDE.md「核心架构原则」;新文件能力先想"它在这个体系里是什么"(文件夹/流转通道),不要另建存储
- **表**:`file_item`(含 `folder_id`;软删除 deleted_at)+ `file_folder`(单层文件夹;删除文件夹时文件回根,不级联删文件);migration V5
- **端点**:`GET/POST /api/files`(list 支持 ids/folderId)、`POST /upload`(可选 folderId)、`DELETE ?ids=`(软删)、`GET /{id}/preview|raw`(raw 支持 Range)、`POST /{id}/index`(RAG 索引,回调 `/{id}/indexed`)、`PUT /{id}/name`(重命名)、`PUT /move`(批量移动, folderId null=根)、`GET /download?ids=`(**zip 打包流式下载**,中文名 RFC 5987)、`GET/POST /folders`、`PUT/DELETE /folders/{id}`
- **前端**:文件页=统一文件系统视图——「Agent 工作区」「媒体缓存」与用户文件夹并列显示(FileTable folderRows);用户文件夹可进入(面包屑)、重命名、删除;文件行操作:预览/下载/重命名/移动到…/删除(菜单);批量:下载(zip)/移动/删除;上传目标跟随当前文件夹
- **媒体缓存 → 文件中心流转**:`POST /api/media/cached/{key}/save`(agent-service 侧,服务端直传 file-service)把派生缓存转为正式资产——用户可见的"保存到文件中心"按钮在媒体缓存页
- **跨服务约定**:其他服务读文件元数据走 `FileService.getById`(Dubbo)或 REST;`FileItem` record 带 `folderId`,构造点增删参数要同步 api/file-api 测试与 file-service 测试

## agent-service 关键链路

- **对话入口** `AgentController POST /api/chat/sessions/{id}/messages`,body `{content, model, reasoningLevel, permissionMode}`;SSE 事件:`step`/`delta`/`reasoning_delta`/`sources`/`approval_required`/`done`/`error`
- **消息引用注入(2026-09-17)**:前端输入框三触发符(对齐 Claude Code:`@` 文件 · `#` 知识库 · `/` 技能与 MCP 工具)把引用序列化为消息尾部固定行(`[引用文件] 名 (file_id=N)` / `[引用知识库] 名 (doc_id=N)` / `[引用技能] 名 (skill_id=N)` / `[引用MCP工具] 名 (tool=mcp__srv__tool)`);`MessageRefResolver` 在 RAG 检索前解析,把真实内容转 CitationDto 注入检索结果**头部**(score=1.0,排在语义命中前,不受 min-score 下限影响)——文件走 file-service 预览(8K 截断)、文档走 `RagRetrievalClient.docChunks`(12K 预算)、技能注入正文(8K,强化"用户指定用此技能")、MCP 工具注入优先调用指令(schema 已在 toolsSpec 不重复);合并结果进 systemPrompt 与 sources 事件。引用行随 content 持久化,历史加载后仍生效;行格式契约与前端 `chatRefs.ts` 两端同步
- **注入可见性(dsh 模式)**:在检索步骤后下发 `type=context` 步骤——`s-context-memory`(form=instructions,工作区引导 SOUL/AGENTS/USER/MEMORY 逐文件元数据+注入正文 + 日记清单)、`s-context-skills`(form=catalog,启用技能条目);**降噪(2026-09-12):步骤仅在首轮或内容较上次下发有变化时下发**——注入本身仍每轮发生(系统提示必须随每次请求携带,LLM 无状态),但不变的轮次不再重复展示(与历史里最近一次同名步骤逐字段对比,一致则跳过;agent 自演化编辑记忆后会重新下发);`ChatStepDto.context` 携带 `{form,kind,files|entries,dailyNotes}`,form 是 producer 声明的信息形态,前端按 form 渲染、未知 form 降级通用展示。文件 `bytes` 为实际注入的 **UTF-8 字节数**(非字符数),`content` 是模型实际读到的注入正文(前端文件行点击展开可见)。注入内容仍进系统提示(`systemPromptWith`),context 步骤只是让注入对用户可见
- **断线重连(后台持续运行)**:SSE 断开不影响编排线程;每轮注册 `TurnStreamRegistry`(内存事件缓冲,step/delta/sources/approval 全量,上限 8000),事件双写(直发+入缓冲)。`GET /sessions/{id}/turn/live` 探测进行中轮次(含缓冲事件数/最后事件),`GET /sessions/{id}/turn/stream`(SSE)先回放缓冲再实时续推直至 done/error,无轮次发 `idle` 即关。前端卸载**不 abort**(切页后端照跑),挂载时探测 live 轮接流恢复;「停止生成」仍走 cancel(真中断)
- **审批** `POST /api/chat/approvals/{token}?sessionId={id}` body `{approved}`;服务端内存保存一次性 token,120 秒超时自动拒绝;模型文字同意不算批准
- **权限三档** `PermissionMode`:ASK(每次询问) / ASSIST(只读自动,写 SQL/容器控制询问) / FULL(全自动);RiskClassifier 第三档 CRITICAL(删数据源、注册/删纳管源)任何档位都强制审批
- **高风险工具**:`execute_write_sql` 经 RiskClassifier → datasource `POST /api/datasources/{id}/execute` → WriteGuard 只允许单条写语句;`manage_container` 调 env-service;`manage_datasource`(list/create/test/schema/remove)与 `manage_service`(纳管源 register/enable/disable/remove/list)走各自管理端点,create 后自动 test,密码不落对话记录;`read_file` 读工作台文件(file-service,先 list 拿 id 再读)
- **MCP 管理**:agent 侧 `manage_mcp`(list/refresh/enable/disable/register/remove)与设置页 `/api/mcp/servers` 共用 `McpServerService`——register 后自动 refresh(测试连接+拉工具清单,失败不回滚注册),list 视图 secrets 已脱敏,register 的 headers/env 值只传服务层(日志经 `scrubArgsForLog` 脱敏、步骤只存 target、不落库);**工具列表/详情** `GET /api/mcp/servers/{id}/tools` 读 tools_cache 快照(名称/描述/inputSchema,不触发远端调用);**风险跟随全局权限档**:list=LOW,其余动作(register/remove/refresh/enable/disable)统一 HIGH(ASK 全问 / ASSIST 询问 / FULL 自动,不做单独强制审批);action 别名 create→register/delete→remove 归一化(分类器/执行层/审批明细三处共用)。**三种传输**:STREAMABLE/SSE(远程,url+headers)与 **STDIO(本地进程,command+args+env)**——STDIO 注册时预检命令(缺 Node.js 等报可操作错误;有 command 无 url 自动推断 STDIO),连接时起子进程(npx 首次下载放宽 120s 超时),disable/delete/关闭时杀**进程树**(Windows taskkill /T + 连接时全树句柄快照兜底——npx 是 cmd→node-cli→node-server 三层,中间层死亡会孤儿化 server,快照句柄不依赖父子链是最后防线);stderr 持续排空进 debug 日志
- **GitHub OAuth 一键登录**(`/api/mcp/oauth/github/**`):设备码流程(与 gh CLI 同款,无需回调地址/内网可用)——`start` 申请 device_code→前端展示 user_code→`poll` 轮询换 token→自动创建/更新名为 github 的服务器为官方端点 `https://api.githubcopilot.com/mcp/`(44 工具含 get_me)并 refresh。GitHub 不支持 DCR,client_id 一次性配置(建 OAuth App 勾 Enable Device Flow;`PUT /client-id` 存 app_setting,`DELETE` 清除;静态兜底 `nora.github.oauth.client-id`);HTTP 走出站代理(`ProxySettingsHolder`);token 只进 mcp_server.headers(API 回读脱敏),日志不打印。前端 MCP 页「GitHub 登录」按钮→`GitHubOAuthModal`(配置引导/验证码展示/轮询/完成态)。npm 包 `@modelcontextprotocol/server-github` 已弃用,统一用远程端点
- **无人值守通道**:`/api/chat/agent/run`(automation)无会话=无审批,CRITICAL 工具在该通道直接 declined;HIGH 按设计放行
- **本机终端 `run_command`**(对齐 Claude Code/Codex 的终端工具):非交互命令(构建/测试/git/npm 等),参数 command/cwd(默认工作区,相对=区内、绝对=整机)/timeout(默认 60s 上限 300s)/shell(powershell 默认、bash=git bash);风险一律 HIGH 跟随全局档位,**不做命令白名单**(假安全),防线=审批卡完整展示命令原文;**PowerShell 用 Nora 内置 pwsh 7.6.6**(`tools/pwsh` 经 git-lfs 分发,首次使用解压到 `pwsh-7.6.6/` 缓存——版本确定不依赖宿主机;解析序:配置 `nora.agent.powershell` → 内置 → PATH pwsh → 系统 powershell.exe 5.1),经 `-EncodedCommand`(Base64 UTF-16LE)免转义执行,强制 UTF-8 输出 + `$ProgressPreference=SilentlyContinue` + `stripClixml` 过滤(npm 首次运行进度会被 PowerShell 序列化成 CLIXML 噪音);bash 优先 git bash 固定路径(裸 bash 会解析到 WSL 转发器报 execvpe 失败);超时/取消杀进程树(taskkill /T + 句柄快照,同 MCP STDIO);无 TTY(交互式程序会挂起到超时,工具描述已警告);输出 200K 字节截断 + `bounded()` 30K/10K
- **编排** `ChatOrchestrationService.chat()`:RAG 检索 → 每轮 `streamTurn` 真流式(JDK HttpClient 逐行读上游 SSE,tool_calls 增量累积到流结束再执行)→ `streamFinalAnswer` 兜底
- **跨轮工具链重建**(2026-09-12,防幻觉):工具步骤持久化脱敏 rawArgs(`StepInput.rawArgs`,与 `scrubArgsForLog` 同口径;非法 JSON/超 20K 不附,旧数据 null 退化纯文本);`buildMessages.appendHistoryMessage` 按 roundIndex 分组重放为 `assistant(tool_calls)+tool(result)` 对——模型看到真实调用记录,而不是纯文本声称结果(实测弱模型会因此编造工具输出/虚构报错);Responses 协议路径天然兼容;预算估算 `wireCostOf` 与 `messageTokens` 同口径
- **usage**:上游最后 chunk(空 choices)带真实 token,跨工具轮累加后 `done.usage` 下发
- **持久化**:会话/消息/step 落 schema_agent;reasoning 聚合后一次性保存(`s-reasoning-{round}`)
- **工作区/记忆(文件系统一体化,对齐 OpenClaw/Hermes)**:agent 的目录(默认 `D:/claude/Nora/agent-workspace`,可配 `nora.agent.workspace`)既是**默认 cwd**也是记忆载体。引导注入按 **Hermes 三层提示词结构**(stable 身份 → context 约定 → volatile 快照):`SOUL.md`(可演化人格:agent 自己改它就改变下轮行为)/`AGENTS.md`(使用约定,从经验中补充)/`USER.md`(偏好)/`MEMORY.md`(耐久事实)——每轮自动注入,逐文件 6k 字+总量 16k 字双预算截断;`memory/YYYY-MM-DD.md` 日记只列清单不注入,按需 `read`。**SYSTEM_PROMPT 只留协议层**(引用标记/行动而非空谈/自演化声明/工作台操作员定位——第 6 条要求「问工作台功能先读手册技能」),人格与任务习惯全部下放到可演化文件(改文件即进化,不改代码)。**「工作台使用手册」技能**(id=7,分类=工作台):九个功能面逐项说明(用户视角+工具映射+常见任务操作指引),用户问工作台能力时 agent `manage_skill read` 读取后回答——改工作台功能时应同步更新该技能正文。「记住…」= 落盘(无隐藏状态)。**工作区是默认目录而非硬沙箱**(OpenClaw 语义):相对路径=区内,**绝对路径=整机**(agent 可读/写项目文件);风险分级 `manage_workspace`——区内写=LOW 自动、区外写=HIGH(ASSIST 询问)、区外删=CRITICAL(任何档位确认),系统目录(Windows/Program Files/盘根)写删硬拒;管理 API `/api/workspace`(GET stats/files/file,PUT file,DELETE file——**仅限区内**,前端管理通道不开放整机)。前端:文件页把工作区渲染为**普通文件夹**「Agent 工作区」(点击进入/面包屑导航/文件编辑器,与真实文件系统一体),非独立页面
- **指令型技能**:`agent_skill` 表(名称/描述/分类/正文),启用技能的**目录**(名称+描述)注入系统提示,正文由 `manage_skill action=read` 按需拉取(渐进披露,不占每轮预算);CRUD API `/api/skills`(列表不返回正文,详情才返回);agent 可在对话中 create/update 沉淀技能(闭环自管)

## 模型/推理注入规则

- 模型解析优先级:请求级模型名 > provider store(设置中心) > 静态 `nora.llm.*` 兜底;不要让环境变量短路 store
- **渠道精确路由(2026-09-16)**:同名模型可跨渠道存在,请求体带 `providerId` → `activeProvider(providerId, model)` 按 id 精确定位(渠道失效时按名回落);解析/重试/标题生成全链路同一渠道,前端 `resolveDefaultProvider` 同序回落,切换菜单按渠道分组、勾选=渠道+模型双匹配
- 思考等级合并:请求级 > 设置页 per-model 默认 > auto;白名单 `reasoningLevels` 约束
- 注入:gpt-5/o 默认 medium;claude 必须带 reasoning_effort;glm 用 `thinking:{type}`;qwen 用 `enable_thinking`;**none 在 effort 通道直传 none**(实测 minimal 关不掉思考);上游拒绝该取值时自动剥档位重试一次,重试成功才记忆(键=端点|模型|档位,换档位仍会尝试)
- **Responses 协议必须带 `reasoning.summary=auto`**,否则 muse 等模型的推理内容(encrypted_content)全被吞

## 已知坑

- **远程媒体链路自动选择(2026-09-17)**:相册中继(home.rainaki.top:8900)同时开公网(nginx TLS)与**局域网明文端口**(megumin:8902,`LAN_PORT`);`RelayMediaRouter` 后台探测中继 `/health` 的 `lanHttpEndpoints`(120s 周期,可达则重写媒体/MCP URL 走内网,失败立即回退)。`MediaCacheService.openUpstream` 与 `McpClientPool`(URL 变更时驱逐重建客户端)都用它。**JDK HttpClient 对明文 http 默认发 h2c 升级**(`Upgrade: h2c` 头)——中继的 WS upgrade 处理器必须对非 /tunnel 路径"拒绝升级、按普通 HTTP 响应"(ServerResponse+assignSocket+error handler),客户端侧对明文强制 `HTTP_1_1`;否则报 "header parser received no bytes" 且 relay 会因未处理 socket error 崩溃重启。**代理直连名单**:`nora.proxy.bypass-hosts`(默认 home.rainaki.top)——该域名指向自家宽带,经 Clash 远程节点绕行实测 +2.3s(直连 0.08s);NetworkController 保存代理时须透传 bypassHosts(静态配置,不随 UI 变化)
- **媒体磁盘缓存 + 原片预取(2026-09-17)**:`/api/media/cache?url=` 代理远程媒体(sha256 键、LRU 2GB、tee 边下边写、Range 切片、7 天浏览器缓存);`/api/media/warm`(POST,前端打开灯箱时调)在手机 Wi-Fi 时后台预取**原片**(X-Net-Kind 头判定,蜂窝跳过;已有 low 档缓存则重拉覆盖)。手机端 `/photo/{id}/video` 转码流与 `/content` 原片是两条 URL,预取要还原 /video→/content。缓存目录用 `NORA_MEDIA_CACHE_DIR` 固定绝对路径(相对路径随启动 cwd 漂移)。**画廊列表预取(同日)**:`GalleryPrefetcher` 在 MCP 工具结果回流时解析 `nora-gallery` 围栏(ChatToolExecutor.execMcp 处,未截断全文),把每条 fullUrl(视频=压缩流/图片=content)后台拉入缓存——用户看列表的窗口里完成手机转码+传输,点开即秒播;`/api/media/cache` 未命中但**有同 URL 预取在途**时 `awaitPrefetch`(20s 上限)等它完成读盘,不发重复请求。**文件中心「媒体缓存」文件夹(同日)**:`GET/DELETE /api/media/cached` 列表/单删/清空——缓存对用户可见可管理(网格预览、档位标注、真实磁盘路径),前端 `MediaCacheBrowser` 挂在文件页,与「Agent 工作区」并列(FileTable folderRows)
- **批量媒体拉取 fetch_media(2026-09-17 晚)**:「把相册一批文件整理到工作区」类任务的一等工具——`MediaFetchService` 调手机 `photos_export`(500/页自动翻页;老 App 回退 photos_search 分页)拿清单 → 4 路并发下载 → `AgentWorkspaceService.writeStreamAny`(.part + 原子移动,不整读内存,单文件上限 2GB)落工作区,进度经 liveOutput 原地刷新。**链路自动选择**:清单 URL 与单文件 `manage_workspace import`/`importToWorkbenchFile` 的 `downloadBounded` 都走 `RelayMediaRouter.preferLan`(在家内网 8902,失败回退公网并 reportLanFailure)。**设计动机(实测事故)**:此前 agent 只能对 407 个 URL 逐个跑 run_command 下载(400+ 轮),且猜 API 路径收 401 触发远端 fail2ban 封出口 IP 1 小时,整轮失败——批量拉取必须是一等工具,不让 agent 自己拼命令。**手机端 0.3.19 配套**:`photos_export` 工具、未知路径 404(不再 401,不触发 fail2ban)、隧道流式响应(大文件边读边发不 OOM)、`/content?q=high` 原片档。**清单为空的 hint 必须透传给模型**(相册名不存在等场景),否则模型无法自纠只能放弃(实测踩过:album="全部" → 吞 hint → 模型直接放弃)
- **fetch_media 进度条 + 停止生成可靠取消(2026-09-18)**:进度经 `ChatStepDto.StepProgress`(phase/done/total/currentIndex/currentFile/active/bytesDone/bytesTotal/bytesPerSec/etaSeconds)搭步骤事件同 id 原地刷新,前端 ToolStepRow 渲染进度卡(进度条+计数+字节+速率+ETA+当前文件+并行路数);**每个进度点只发一条 progress 事件**——同时调 text() 会发第二条同 id 事件把 progress 字段覆盖掉,进度卡永远不出现(实测 bug)。**取消双轨**:`TurnCancellation` 会话级标志(cancel 端点置,编排循环 `isTurnCancelled`=中断 OR 标志,MediaFetchService 派发前+每块字节轮询)——线程中断标志会在 JDBC/SSE 链路上被下游消费,不可靠(实测:取消后继续跑完 10GB);轮次开始/收尾清标志。**传输限速(手机端 0.3.21)**:SpeedLimit 全局令牌桶(整机上行总带宽语义,并发流共享),作用于 contentStreaming 读取侧;中继配合——缓存命中的字节不经手机,手机推 speed 消息给中继,中继对缓存命中与转发路径共用同一个全局桶(两路各自限速会叠加,实测 2MB/s 设定跑出 4MB/s)
- **agent 能力 P0/P1 打磨(2026-09-18,sess-1789704322789 复盘)**:四类上下文/语义缺口修复——①系统提示**注入当前时间**(ChatContextAssembler.currentTimeLine,否则模型只能 run_command Get-Date 现查);②手机端 `to` 纯日期**补到当天末刻**(parseIsoEnd:to=9-16 此前=当天 0 点,from=to=同天返回空区间);③fetch_media 结果带**解析后的绝对路径**(只回显 folder 参数时模型要再跑 PowerShell 确认落点);④AGENTS.md 约定**媒体放 photos/、导出前先查已有**。文件域补齐:`manage_workspace` 加 **move/copy/mkdir**(此前整理只能借 PowerShell)+ **通配符批量**(`VID_*.mp4` 一次移动)+ list 目录**递归摘要**(文件数+大小,不再 Get-ChildItem 数)。E2E:原 16 调用/62s 的'搞个文件夹放进去'→ 3 调用/9s,且不再重复下载(先 list 发现已有)
- **fail2ban 误封教训(2026-09-17)**:megumin 的 phone-album jail 原先统计**任意 401**(filter 为 `.* 401`)——agent 探测猜路径收到一串 401 即把出口 IP 封 1 小时(封禁=丢包,表现为 TLS 握手超时,"网络不稳"实为自家防护)。双层修复:手机端未知路径返回 404(不参与鉴权),filter 收紧为只匹配 `"(?:GET|POST|PUT|DELETE) /(?:tunnel|mcp)[^"]*" 401`。**排障提示**:PowerShell 请求走系统代理(出口 IP=代理节点)、Java 直连不走——两者行为不一致时先想「是不是出口 IP 被限」
- **Redis 是可选平台组件**(2026-09-12 接入,`nora.redis.*` 配置,`NORA_REDIS_ENABLED` 开关):nora-common 的 `NoraRedis` 门面(懒连接/断线自动重连/不可达时静默降级,永不阻断启动与请求)。已接两处——**rag-service 嵌入缓存**(`nora:embed:<sha256(model:dim:text)>`,7 天 TTL,重复文本不再调 Jina;命中 55ms vs 未命中 847ms)与 **agent-service 审批票据**(`nora:approval:ticket:*` + `nora:approval:session:*`,TTL 150s;跨实例 resolve 走 `nora:approval:resolved` pub/sub 广播,`pendingFor` 在本地为空时从 Redis 兜底读)。写新落点前先想清楚"Redis 挂了怎么办"——一律降级,不做硬依赖
- **异常处理系统(2026-09-12)**:错误按 `ErrorCategory`(10 类:VALIDATION/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/CONFLICT/RATE_LIMITED/DEPENDENCY/UNAVAILABLE/TIMEOUT/INTERNAL)分类——新代码抛语义化 `BusinessException.dependency("DS_CONNECT_FAILED","消息","hint")` 等工厂;旧 `BusinessException(400,...)` 自动映射分类(零改动但不是新写法)。错误信封带 `category/errorCode/hint/retryable/traceId`(成功响应保持三段形状,data 为 null 也不省略——前端 isEnvelope 依赖 data 键)。**服务间 RestClient 必须配 `defaultStatusHandler(HttpStatusCode::isError, EnvelopeErrorHandler.create())`**(双参重载!单参是 legacy 接口)——非 2xx 时信封 message 提取为干净异常,否则 catch 块回填的是 Spring 异常串噪音。LLM 上游等外部客户端不配(非 Nora 信封,错误格式各异)
- **时区全局约定**:`nora.timezone`(默认 Asia/Shanghai)统一三处——JVM 默认时区(nora-common `TimeZoneConfig` 静态块生效)、Jackson 序列化、DB 会话(`ALTER DATABASE nora SET timezone`,已固定)。DB 时间列一律 `timestamp without time zone` 存本地挂钟时间,Java 读取必须用 `LocalDateTime`(用 `OffsetDateTime` 读会被 JDBC 贴错 UTC 标签,前端 +8h);前端解析 `created_at` 走 `toHm()`(agentApi)手动拆解,不用 `new Date()`
- **SSE 上游用 JDK HttpClient 流式读取**,不要用 RestClient `.body(byte[].class)`(伪流式),其字符串转换器还会把 text/event-stream 按 ISO-8859-1 弄乱中文
- 测试 mock:`ModelProviderServiceTest` 用 Strict stubs,参数不匹配直接报 PotentialStubbingProblem
- 工具输出 30K/10K 截断;循环熔断(同参数 3 次阻断)
- execute_sql guardrail:单条 SELECT/SHOW/EXPLAIN
- **取消语义**:cancel/超时中断编排线程后,JDK HttpClient 阻塞读抛 `IOException(InterruptedException)` 进 streamUpstream 的 `failed` 结果——编排层到处检查 `Thread.currentThread().isInterrupted()`/`isInterruption(cause)` 短路返回 `failedFuture(CancellationException)`,**任何空响应重试/最终回答兜底都必须排除中断**,否则取消变成多烧一整轮 token 且取消轮被当正常完成落库;控制器收尾的 `answer` StringBuilder 靠 `delta()` append 维持半截内容
- **`.env.local` 启动自动加载**:nora-common `DotenvEnvironmentPostProcessor`(spring.factories 注册)以最低优先级加载 `nora-api/.env.local`,真实环境变量优先;key 找不到会走默认空值 → rag 报 "embedding not configured"、外网调用直连超时(需 `NORA_PROXY_ENABLED=true`)——排障先查 dotenv 日志与 key/代理
- **env-service FILE 源路径**:纳管源日志路径须指向 `D:\claude\Nora\logs\<svc>-text.log`(logback 输出;`<repo>/<svc>.stdout.log` 与 `/opt/nora/logs/*.out.log` 均已失效,V9 迁移负责修正);判断文件存在必须 `isFile()`——`File.lastModified()` 对不存在文件返回 0 不抛异常,会算出「日志活跃于 497091 小时前」并吞掉「文件不存在」分支

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

- `docs/agent-permission-and-tools-design.md` — **权限/风险/工具权威参考**(改 RiskClassifier/toolsSpec/审批前必读,改后同步)
- `docs/agent-implementation-spec.md` — 执行协议/审批协议规格(初版设计,数字已演进)
- `docs/harness-tool-calling-research-2026-09-05.md` — harness 调研
