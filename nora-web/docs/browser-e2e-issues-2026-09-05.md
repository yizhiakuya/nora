# Nora 浏览器业务测试记录

日期：2026-09-05
环境：nora-web http://localhost:3001，gateway http://localhost:8080，VITE_USE_BACKEND=true

## 已验证

- 首页、文件、对话、知识库、AI 能力、数据源、环境控制台、自动任务、设置路由均可打开。
- 普通对话经网关返回完整 SSE：step、sources、delta、done。
- SQL 查询请求会实际调用 execute_sql；不存在的表会返回真实数据库错误。
- 服务诊断请求会实际调用 read_service_logs，并根据 nora-redis 真实日志生成结论。
- DROP TABLE 请求未执行破坏性操作。

## 已发现问题

1. 真实模型未配置时，前端只能显示配置提示；需要在设置中心完成 Provider 持久化后再验收真实模型。
2. ✅（2026-09-14 已闭环——**经确认为无此需求，整块移除**）「数据图谱」tab、`DataGraphView`、`GraphProject`、`graphReady` 全部删除。该功能自 2026-09-03 引入即为纯 Mock，后端从未设计（`architecture-v2.md` 无 Phase 5），数据源/文档/服务也无「项目」归属实体。
3. 首页知识库模型名称仍读取 MOCK_INDEX_STATS.model，统计展示和后端可能不一致。
4. AI 能力中心、部分通用设置和环境变量仍是本地 Zustand/localStorage，没有后端持久化。
5. 自动任务的自然语言修复动作与后端当前 SQL 动作执行器不一致，环境诊断创建的修复任务不能直接执行。
6. 高风险操作审批协议已经定义，但 approval_required 事件、审批 API 和前端批准 UI 尚未实现。
7. 浏览器端上传真实文件、创建真实数据源、执行真实 SQL 的完整 UI 路径仍需在配置好可用测试数据后补测。
8. 对话页的业务反馈仍偏弱：模型决策步骤标题过于泛化，工具执行没有展示结构化结果摘要，用户很难判断 Agent 做了什么。
9. RAG 引用分数可以低于 50%，但页面没有“低置信度”提示，容易让用户把弱相关片段当成可靠依据。
10. ✅（2026-09-08 已修复）新增 POST /api/rag/index/text 文本入库接口，「保存到知识库」真实入库（name-keyed 同名覆盖，可检索/跨设备）；浏览器端到端验证：点击→DB id=18 indexed→向量检索命中。
11. 对话侧栏标题直接使用首条消息，长问题会造成会话难以识别，也没有显示最后活动时间或执行失败状态。
12. ✅（2026-09-08 已修复）上下文预算体系落地（见 nora-api/docs/context-management-design.md）：83% 触发轮内微压缩、上游超限硬压缩重试、done 下发 contextWindow/promptTokens/ttftMs，进度条吃服务端值。

## 处理状态

- 已修复：Agent SSE error 前端展示、SQL Guardrail、工具日志参数边界、RAG 文档真实同步入口、模型决策步骤结束后仍显示“执行中”。
- 本轮推进：对话步骤支持点击展开/收起，并展示工具参数与结果摘要；上下文用量改为按当前会话消息和输入实时估算，达到 75%/90% 自动变色；Agent 已从持久化 Provider 表读取启用的端点、密钥和模型，不再只依赖环境变量。
- 待处理：设置持久化、结构化自动任务动作、配置数据后的浏览器回归。（图谱 API 已于 2026-09-14 确认无需求并移除）
- 2026-09-08：上下文 token 已由服务端计量（done 下发 usage/promptTokens/contextWindow/ttftMs），保存到知识库已接真实入库。
