# nora-web — 前端

React 18 + Vite(3001) + Tailwind + shadcn 风格 ui + Zustand persist。

## 关键链路

- `lib/api/client.ts` — 统一请求:envelope(`{code,data,message}`,code=0 成功)、默认 30s 超时(调用方显式传 signal 时以其为准)。**前端无 Mock 模式(2026-10-01 删除)**:所有域直连真实后端
- `lib/api/agentApi.ts` — SSE 解析(reasoning_delta 聚合为 s-reasoning-N step)
- `lib/chatRefs.ts` + `components/chat/RefChip.tsx` + `components/chat/ReferencePicker.tsx` — 输入框引用体系(2026-09-17):**三触发符分工**(对齐 Claude Code/Codex):`@` 文件中心 · `#` 知识库文档 · `/` 技能与 MCP 工具;内联菜单(↑↓/Enter/Esc、光标位置感知)、拖拽文件上传、📎 附件多选;引用以固定行格式序列化在消息尾部(`[引用文件] 名 (file_id=N)` / `[引用知识库] 名 (doc_id=N)` / `[引用技能] 名 (skill_id=N)` / `[引用MCP服务器] 名 (server_id=N)`)——随 content 持久化、历史可解析;后端 `MessageRefResolver` 解析后把真实内容注入上下文(排在检索命中前;MCP 按服务器 tool_policy 生成 eager/lazy 对应指引,前端只写中性文案)。改行格式必须两端同步(chatRefs.ts ↔ MessageRefResolver.java)
- `hooks/useModelProviders` — provider 唯一数据源(Zustand persist + 后端同步)
- `components/chat/AgentThoughtBlock.tsx` — 思考块(roundIndex 分组;think 行默认展开,用户收起后尊重用户);`ContextRow` 渲染注入上下文步骤(dsh 模式:form=instructions 显示文件清单+日记清单,**文件行可点击展开注入正文**;form=catalog 显示技能条目;折叠一行摘要,展开只读;未知 form 降级通用展示)。**已拆子组件(2026-09-17)**:工具行在 `ToolStepRow.tsx`(chip+参数/结果展开+MCP 图片灯箱/画廊),注入行在 `ContextStepRow.tsx`(含 ContextFileRow);主文件只留推理行+StepRow 分发+三个导出
- `components/chat/ArtifactsBlock.tsx` + `lib/artifacts.ts` — **产物画廊(2026-09-18 v2,原生渲染)**:agent 在回答末尾附 ` ```nora-artifacts ` 结构化 JSON(title/summary/stats/sections),前端原生渲染成画廊卡片——**媒体网格复用 ImageLightbox 灯箱、文件行 open 深链直达**(与手机相册画廊同款体验;v1 的 AI 自写 H5 方案已废弃:质量不可控/无原生交互)。`open` 深链:workspace:<路径>(files 页 `?workspace=` 深链,list 判断目录性→目录直达/文件进父目录)/ file:<id>(`?open=` 预览)/ url:。挂载 `MarkdownContent` + `ToolStepRow` 两处(通用协议)
- `components/settings/model/ReasoningLevelConfig.tsx` — per-model 推理等级配置
- `hooks/useSkills` — 技能唯一数据源(列表走 /api/skills 不带正文;详情按需 loadDetail 拉正文——渐进披露);后端模式 CRUD 乐观更新+失败回滚
- `components/files/WorkspaceBrowser.tsx` — 文件页内的「Agent 工作区」文件夹浏览器(文件系统一体化:普通文件夹形态,点击进入/面包屑导航/文件编辑器);`app/files/page.tsx` 持 `workspaceDir` 状态(null=根视图)
- `components/files/IndexToKnowledgeModal.tsx` — 「加入知识库」配置弹窗(F5,2026-09-26):目标资料库/分段模式(结构分段·父块-子块)/高级参数(chunkSize/overlap/separator)/**试切预览**(`previewChunks` + `filesApi.fetchPreviewText`);确认后 `indexFileFromBackend(fileId, name, chunkConfig, baseId)` 带参提交(此前只发 fileId/name,后端参数被忽略)
- `lib/executionStatus.ts` — **执行终态统一展示映射(B5,2026-09-27)**:`EXECUTION_STATUS_META`/`executionStatusMeta(status)`(icon/cls/label/actionHint 四元组)——执行历史、首页「最近任务结果」、资料页共用同一份,改状态语义只改这里(此前首页/成果页 `success ? 成功 : 失败` 二分,同一条记录在不同入口状态不一致)
- `app/page.tsx` 首页输入区 — **发送前加资料(B3,2026-09-27)**:📎 文件 / 📄 知识库按钮(复用 `ReferencePicker`),选中引用以 chips 显示可删;发送时随 URL `refs=` 带进新会话(对话页解析为 chips、发送时序列化引用行)。此前首页只能发纯文本,需要资料的请求先执行、资料后补
- `hooks/useCommandPalette.ts` + App 的 RouteShell — **全局搜索(§7,2026-09-27)**:面板实例与 Cmd+K 监听挂 RouteShell(任何页面可呼出),开关走共享 store(首页搜索框点击打开同一实例);面板懒加载 + 仅打开时渲染(主入口包体门禁,静态引入会进主包)
- `lib/services/savedArtifactsApi.ts` + `components/files/SavedArtifactsView.tsx` — **已保存成果(B1,2026-09-27)**:对话保存(文件/知识库)成功后登记服务端(`POST /api/saved-artifacts`),资料页「已保存成果」tab 读它渲染——类型徽章/打开(工作区深链 `?workspace=`)/回到来源会话(`/chat?session=`);执行记录留在任务页,不混入成果
- `components/shared/HandoffChoiceDialog.tsx` + `lib/handoff.ts` — **跨页交接去向选择(B6,2026-09-27)**:`buildAssistantHandoffUrl(prompt, refs, newSession=true)` 默认带 `new=1`(跨页发起=新建处理);文件页批量栏弹「新建处理/加入当前对话」选择;chat 页消费 `new=1` 建新会话(用后清参数)。`TaskContextPayload.retrievalScope`(B4):「限定检索」开关开启时随 context 下发 docIds
- `lib/services/workspaceApi.ts` — 工作区 API 接入层(stats/files/read/write/delete)

## 约定

- 前端不内置 Mock,不造假数据;接口失败如实报错(humanize + hint),空态引导操作
- 后端模式错误要 humanize:错误文案 + hint + 技术详情(errorRaw 折叠)+ 会话 ID 可复制
- UI 文案中文;样式用 Tailwind + CSS 变量(bg-card/text-foreground/border 等),明暗两套都要看一眼

## 已知坑

- **双健康探针(B7,2026-09-27)**:`useBackendHealth` 拆两个——`online` 探 `/api/auth/status`(gateway 自身端点,只有网关不可达才全屏替换)、`agentOnline` 探 `/chat/health`(只影响助手/对话区域局部提示,文件/任务/数据源不受影响)。此前唯一探针是 chat/health,agent 挂了整站被替换成「服务不可用」,其他服务可用也无法导航。新增探针目标时想清楚「挂了影响哪个区域」,不要回到单探针
- **错误结构化优先(异常处理系统 2026-09-12)**:`client.ts` 的 `requestJson` 非 2xx 时解析后端信封为 `ApiError`(带 category/errorCode/hint/retryable/traceId);`humanizeError` 优先按 `category` 映射文案(ApiError 入参),字符串启发式仅作兜底(网络异常/网关 HTML/旧接口)。新增错误 UI 时先传 Error 对象、不要先 `.message` 提取字符串
- HMR 对重命名导出不可靠,报 "does not provide an export" 先整页 reload 再判断
- Zustand persist 的 store 改字段结构时注意兼容旧 localStorage 数据
- 链路追踪:`client.ts` 每页面会话生成 browserTraceId,随全部请求走 `X-Nora-Trace-Id`;全局错误经 `lib/errorReporter.ts` 上报 `POST /api/log/frontend`(10s 去重);500 错误消息带服务端 `[trace=…]`,可与后端日志交叉检索
- **sonner 打了 pnpm 补丁(`patches/sonner@2.0.8.patch`,2026-09-21)**:去掉「`document.hidden` 时暂停自动关闭」——内嵌预览环境(Claude Electron)会把正在显示的页面长期报成 hidden,导致 toast 计时器永不恢复、通知堆屏不消失(实测挂 4 分钟+)。升级 sonner 时需重打补丁,或确认上游已有等价开关

## 测试

```bash
pnpm exec tsc --noEmit
pnpm exec vitest run   # 126 用例
```
