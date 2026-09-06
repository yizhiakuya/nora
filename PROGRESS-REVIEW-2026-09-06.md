# Nora 项目开发进度评估

评估日期：2026-09-06 · 分支：`main` · 最新提交：`08b07c7`（2026-09-05）
评估依据：代码实际状态 + `nora-api/docs/architecture-v2.md` 的 Phase 0–4 路线图 + `nora-web/docs/browser-e2e-issues-2026-09-05.md` 问题清单 + 工作区未提交改动

> **2026-09-06 后续更新**：本文档中 C 类（工程与文档）待办 15–19 已全部修复，
> 涉及 `AGENTS.md`、`nora-api/README.md`、根 `README.md`、`nora-web/README.md`、
> `nora-web/docs/rag-service.md` 与 `ragService.ts` 注释。前端质量门已回归验证：
> typecheck ✅ · lint ✅ · test 70/70 ✅。风险 R4 降级为已缓解。

---

## 一、整体结论

| 指标 | 数值 |
|------|------|
| 整体完成度 | **约 72%** |
| 后端服务 | 7 个微服务全部有实现（agent / file / rag / datasource / env / automation / gateway） |
| 后端代码 | 67 个 Java 源文件，23 个测试类 |
| 前端代码 | 135 个 ts/tsx，组件 6,064 行，9 个页面 899 行，15 个测试文件 / 70 个用例 |
| 前端质量门 | typecheck ✅ · lint ✅ · test 70/70 ✅ |
| 后端质量门 | ⚠️ **本轮无法验证**（Maven 环境损坏，见风险 R1） |
| 工作区未提交 | 11 个修改文件 + 8 个新文件，约 975 行 |

**一句话**：Phase 0–4 的主干链路均已打通并经浏览器实测，当前工作重心是 Phase 2 的收尾项「高风险操作审批流」；真正的缺口在**后端尚未覆盖的 3 个域**（通知、图谱、设置持久化）和**未验证的构建链路**。

---

## 二、已完成模块（✅）

### Phase 0 · 基础设施 — 100%

- Maven 多模块骨架：16 个模块（`nora-parent` / 6 个 `api-*` / 2 个 `common-*` / 7 个 service）
- Docker Compose 基础设施（PG16+pgvector :5432 / Redis :6379 / Nacos :8848）
- gateway-service 路由全部就绪：`agent` `/api/chat/**`、`file` `/api/files/**`、`rag` `/api/rag/**`、`datasources`、`automations`、`environment`
- Spring Cloud Alibaba 服务发现 + LangChain4j BOM 1.0.1

### Phase 1 · RAG 链路 — 90% ✅

- **file-service**：上传 + Tika 解析 + 存储 + 预览 + 索引触发 + 索引回写 + 删除（6 端点）
- **rag-service**：分块（2000 字 / 200 重叠）→ Jina embeddings-v3（1024 维）→ pgvector HNSW 存储 → 余弦检索 + 引用 + 索引统计（6 端点）
- 前端知识库已接后端：文档列表、检索测试、索引统计

### Phase 2 · Agent 链路 — 85%（收尾中）

- SSE 事件协议完整：`step` / `delta` / `reasoning` / `sources` / `done` / `error`
- ReAct 工具循环（最多 5 轮）：`execute_sql`、`read_service_logs`
- 会话持久化：消息历史、会话列表、删除
- 模型 Provider 管理：CRUD + 连通性测试 + 自动发现模型列表
- 真实 token 用量回传（done 事件 usage）+ 上下文计量条按 75%/90% 变色
- 逐步思考时间线 + 每模型 reasoning level
- 工具调用步骤可展开，展示参数与结果摘要

### Phase 3 · 数据源 + 自动任务 — 75%（部分）

- **datasource-service**：连接 CRUD / 连通测试 / Schema 浏览 / 只读查询 / 执行历史（8 端点）+ `SqlGuard` 只读护栏（已有单测）
- **automation-service**：规则 CRUD / 启停 / 手动触发 / 执行历史（8 端点）+ Quartz 调度 + SQL 动作执行器
- 缺口：Phase 3.5「通知 SSE + 未读角标」**后端零实现**

### Phase 4 · 环境控制台 — 65%（部分）

- **env-service**：真实 Docker 容器列表 / 启停 / 重启 / 日志 SSE（6 端点）
- AI 诊断：经 `read_service_logs` 由 Agent 生成结论（浏览器实测通过）
- 缺口：Phase 4.4「诊断 → 创建自动化修复任务」链路未打通（前端自然语言动作 vs 后端 SQL 执行器语义不一致）

### 前端 UI 层 — 95% ✅

9 个页面全部可用：首页 / 文件 / 对话 / 知识库 / AI 能力 / 数据源 / 环境 / 自动任务 / 设置。
已接后端：`useFiles` `useUpload` `useFileViewer` `useKnowledgeDocs` `useConnections` `useServices` `useAutomations` `useModelProviders` `useChat`。

---

## 三、进行中（🔄 未提交）

**任务：高风险操作审批流**（`docs/agent-implementation-spec.md` 定义的审批协议，此前状态为"已 spec 未实现"）

后端新增（7 个文件）：
- `ApprovalService` — 服务端审批状态，一次性 UUID token，120 秒超时自动拒绝，模型文本"同意"不生效
- `RiskClassifier` — 工具调用风险分级 + 写操作/DDL 拒绝文案
- `PermissionMode` — `ASK` / `ASSIST`（默认）/ `FULL` 三档会话权限
- `WriteSqlClient` · `ContainerControlClient` — 写 SQL 与容器控制的新工具客户端
- `WriteGuard`（datasource-service）— 写操作护栏
- `ApprovalRequestDto` + `AgentController` 新增 `POST /approvals/{token}`、`GET /sessions/{id}/approvals`

前端新增/修改：
- `ApprovalCard.tsx`（新增）— 批准/拒绝卡片，已在 `ChatMessageItem` 中挂载
- `agentApi.ts` — 处理 `approval_required` SSE 事件 + `resolveApproval`
- `ChatInputArea` — 权限模式选择器；`useChat` — permissionMode 透传

**状态判断**：代码已前后端打通、结构完整，但**尚未编译验证、尚未测试、尚未提交**（约 975 行改动直接堆在 `main` 工作区）。

---

## 四、待办事项（⬜）

### A. 设计要求但尚未做（来自实施规格与架构文档）

1. `agent_step` 持久化从"轮次结束时"前移到"每个事件发送处"
2. 日志读取 Guardrail 及单元测试（SQL 侧已有 `SqlGuardTest`，日志侧缺失）
3. 三类集成测试：模型未配置、上游超时、SSE 中断
4. traceId 与结构化日志
5. Phase 3.5 通知 SSE + 未读角标（后端零实现，前端 `useNotifications` 仅本地）
6. Phase 4.4 诊断结果 → 自动创建可执行的修复任务（结构化动作定义）
7. 知识图谱 API（前端 `DataGraphView` 仍读 `MOCK_GRAPH`）
8. RocketMQ 事件总线：当前 Phase 1/2 用同步 REST 替代（设计偏差已在代码注释标注）

### B. 浏览器实测遗留问题（2026-09-05 记录，未闭环）

9. 设置中心 / AI 能力中心 / 环境变量 → 后端持久化（现为 Zustand + localStorage）
10. "保存到知识库"按钮只写前端，未调用后端索引
11. RAG 检索低置信度（<50%）缺少提示
12. 会话侧栏标题取首条消息、无最后活动时间与失败状态
13. 配置真实 Provider 后的完整浏览器回归
14. 上下文 token 仍是前端估算（真实 usage 已回传，前端未完全切换）

### C. 工程与文档

15. ~~更新 `nora-web/AGENTS.md`~~ ✅ 已修复（2026-09-06）
16. ~~更新 `nora-api/README.md` 状态表~~ ✅ 已修复
17. ~~清理 `ragService.ts` 中 3 处过时 TODO~~ ✅ 已修复
18. ✅ 已修复：根 `README.md` 与 `nora-web/README.md` 的"纯前端 / 后端接入预留"表述
19. ~~`nora-web/docs/rag-service.md` 口径统一~~ ✅ 已修复（第 5 章改为已完成的接入方式说明）

---

## 五、进度风险

| # | 风险 | 等级 | 说明与建议 |
|---|------|------|-----------|
| R1 | **后端构建链路不可用** | 🔴 高 | `M2_HOME` 为空，`mvn` 报 `ClassNotFoundException: plexus.classworlds.launcher`（Maven 装在 `D:/tools/apache-maven-3.9.16`，jar 存在但启动失败）。后果：975 行审批流代码零编译/测试保护，Phase 0.4 要求的每模块 `mvn verify` 无法执行。**先修环境再推进** |
| R2 | **975 行改动裸奔在 main** | 🔴 高 | 未建特性分支、未提交。任何 `git checkout` / 回滚都会丢失。建议立即建分支提交 |
| R3 | **写能力扩大但审批未验证** | 🔴 高 | 新增 `WriteSqlClient` / `ContainerControlClient` 打开了破坏性操作面，审批流是唯一护栏，却尚未跑通一次真实请求。上线前必须做"DROP TABLE / 停容器"的端到端拦截验证 |
| R4 | ~~文档与实现脱节~~ | 🟢 已缓解 | 2026-09-06 已修复：`AGENTS.md`（新增「后端接入现状」表与契约层约束）、`nora-api/README.md`（Phase 状态 + 服务端点 + 已知偏差）、根 `README.md`、`nora-web/README.md`，并清理 `ragService.ts` 过时 TODO。剩余项已一并处理，文档口径现已统一 |
| R5 | 前端双轨运行 | 🟡 中 | 知识库/技能/设置/首页仍读 `MOCK_*`，`USE_BACKEND` 开关两侧行为可能分叉，长期维护成本上升 |
| R6 | 测试以单测为主 | 🟡 中 | 后端 23 个测试类、前端 70 个用例均为单元测试；SSE 透传、多服务链路无集成测试 |
| R7 | 事件驱动缺失 | 🟢 低-中 | RocketMQ 未接入，file→rag 用同步 REST。Phase 3 事件触发会放大这个缺口 |
| R8 | 通知域无后端 | 🟡 中 | 前端通知铃铛纯本地，跨设备/刷新不可见，自动任务执行结果无法触达用户 |

---

## 六、建议的下一步顺序

1. **修复 Maven 环境** → 跑通 `mvn install`，先把审批流代码纳入编译与测试保护
2. **建分支提交审批流**（`feat/agent-approval-flow`），并补审批相关单测
3. **端到端验证审批拦截**：构造 DDL / 批量删除 / 停容器请求，确认全部进入 `approval_required` 且未执行
4. 补 traceId + 三类集成测试（未配置模型、上游超时、SSE 中断）
5. 打通 Phase 4.4 诊断 → 自动修复任务（先定义结构化动作协议）
6. 补齐后端缺口：通知 SSE、图谱 API、设置持久化
7. 同步更新 AGENTS.md / README，清理过时 TODO
