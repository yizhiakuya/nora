# nora-api — 后端微服务

## 服务与端口

gateway(8080) → file(8081) / rag(8082) / agent(8083) / datasource(8084) / env(8085) / automation

## agent-service 关键链路

- **对话入口** `AgentController POST /api/chat/sessions/{id}/messages`,body `{content, model, reasoningLevel, permissionMode}`;SSE 事件:`step`/`delta`/`reasoning_delta`/`sources`/`approval_required`/`done`/`error`
- **审批** `POST /api/chat/approvals/{token}?sessionId={id}` body `{approved}`;服务端内存保存一次性 token,120 秒超时自动拒绝;模型文字同意不算批准
- **权限三档** `PermissionMode`:ASK(每次询问) / ASSIST(只读自动,写 SQL/容器控制询问) / FULL(全自动);RiskClassifier 第三档 CRITICAL(删数据源、注册/删纳管源)任何档位都强制审批
- **高风险工具**:`execute_write_sql` 经 RiskClassifier → datasource `POST /api/datasources/{id}/execute` → WriteGuard 只允许单条写语句;`manage_container` 调 env-service;`manage_datasource`(list/create/test/schema/remove)与 `manage_service`(纳管源 register/enable/disable/remove/list)走各自管理端点,create 后自动 test,密码不落对话记录
- **编排** `ChatOrchestrationService.chat()`:RAG 检索 → 每轮 `streamTurn` 真流式(JDK HttpClient 逐行读上游 SSE,tool_calls 增量累积到流结束再执行)→ `streamFinalAnswer` 兜底
- **usage**:上游最后 chunk(空 choices)带真实 token,跨工具轮累加后 `done.usage` 下发
- **持久化**:会话/消息/step 落 schema_agent;reasoning 聚合后一次性保存(`s-reasoning-{round}`)

## 模型/推理注入规则

- 模型解析优先级:请求级模型名 > provider store(设置中心) > 静态 `nora.llm.*` 兜底;不要让环境变量短路 store
- 思考等级合并:请求级 > 设置页 per-model 默认 > auto;白名单 `reasoningLevels` 约束
- 注入:gpt-5/o 默认 medium;claude 必须带 reasoning_effort;glm 用 `thinking:{type}`;qwen 用 `enable_thinking`;none→minimal/disabled
- **Responses 协议必须带 `reasoning.summary=auto`**,否则 muse 等模型的推理内容(encrypted_content)全被吞

## 已知坑

- **SSE 上游用 JDK HttpClient 流式读取**,不要用 RestClient `.body(byte[].class)`(伪流式),其字符串转换器还会把 text/event-stream 按 ISO-8859-1 弄乱中文
- 测试 mock:`ModelProviderServiceTest` 用 Strict stubs,参数不匹配直接报 PotentialStubbingProblem
- 工具输出 30K/10K 截断;循环熔断(同参数 3 次阻断)
- execute_sql guardrail:单条 SELECT/SHOW/EXPLAIN

## LLM 通道

agent-service → sub2api 中转 `http://192.168.0.109:28765/v1`(内网直连);provider 存 model_provider 表(api_key 明文,key 不回传,mask 后展示);模型发现:测试连通时 GET /models

## 测试

`mvn -pl services/agent-service test`(21 用例)

## 文档

- `docs/agent-implementation-spec.md` — 执行协议/审批协议规格
- `docs/harness-tool-calling-research-2026-09-05.md` — harness 调研
