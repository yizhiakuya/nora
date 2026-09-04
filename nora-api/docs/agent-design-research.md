# Agent 设计调研笔记 · 论文与业界实践

> 状态：调研完成，结论已回写 architecture-v2.md 第 4.8-4.11 节  
> 日期：2026-09-04  
> 目的：为 agent-service 的执行循环、工具接口、记忆、安全设计提供依据

---

## 一、论文

### 1. ReAct: Synergizing Reasoning and Acting in Language Models

- **作者**：Shunyu Yao, Jeffrey Zhao, Dian Yu, Nan Du, Izhak Shafran, Karthik Narasimhan, Yuan Cao
- **机构**：Princeton + Google Brain
- **年份**：2022 · arXiv:2210.03629 · ICLR 2023

**核心思想**：让 LLM 以交错方式生成 reasoning trace（思考）和 task-specific action（行动），二者互相增强：

- reasoning trace 帮助模型归纳、追踪、更新行动计划，处理异常
- action 让模型与外部环境（知识库/工具）交互并获取信息反馈

**关键结论**：
- HotpotQA/Fever：与单纯 chain-of-thought 相比，减少幻觉与错误传播
- ALFWorld/WebShop：绝对成功率分别提升 34% 和 10%（仅用 1-2 个 in-context example）
- 人类可解释性显著提升：可看到完整 Thought → Action → Observation 轨迹

**对 Nora 的启示**：这是 agent-service 的基础执行循环。前端 `ChatStep` 已天然对齐——think type 对应 Thought，tool type 对应 Action+Observation。

---

### 2. Reflexion: Language Agents with Verbal Reinforcement Learning

- **作者**：Noah Shinn, Federico Cassano, Edward Berman, Ashwin Gopinath, Karthik Narasimhan, Shunyu Yao
- **机构**：Northeastern + MIT + Princeton
- **年份**：2023 · arXiv:2303.11366 · NeurIPS 2023

**核心思想**：Agent 失败后不更新权重，而是把失败轨迹转为自然语言反思（verbal feedback），存入 episodic memory，下次尝试时作为额外上下文注入。

```
Actor(执行) → Evaluator(打分) → Self-Reflection(自然语言批评)
                    ↓
              episodic memory → 下次尝试前注入
```

**关键结论**：
- HumanEval 编程：从 80.1% 提升到 91%（pass@1）
- 无需梯度训练，纯"语言级强化学习"

**对 Nora 的启示**：任务执行失败（如 SQL 查询报错、日志诊断无结果）后，把失败原因写入 agent 的反思记忆，下次同类问题自动带上"上次失败原因"作为上下文。

---

### 3. ReWOO: Reasoning Without Observation

- **作者**：Binfeng Xu et al.
- **年份**：2023 · arXiv:2305.18323

**核心思想**：将 ReAct 的"推理-观察交错"改为三阶段分离——Planner 一次性生成完整计划，Worker 并行执行工具调用，Solver 汇总所有证据生成最终答案。

```
ReAct:  T1→A1→O1→T2→A2→O2→...→Tn（每步都要带完整历史）
ReWOO:  Planner(一次生成全部计划) → Workers(并行执行) → Solver(一次汇总)
```

**关键结论**：
- HotpotQA 上 token 效率 **5×提升** + 准确率 4% 提升
- Planner / Worker / Solver 可分别微调到小模型（175B→7B）
- 工具失败时更鲁棒（Solver 可以判断证据缺失并降级回答）

**对 Nora 的启示**：当用户请求包含多个独立工具调用（如"查 orders 统计 + 看服务日志 + 检查知识库文档"），优先用 ReWOO 模式并行执行而非串行 ReAct，节省 token 并降低延迟。

---

### 4. Voyager: An Open-Ended Embodied Agent

- **作者**：Guanzhi Wang et al.
- **机构**：NVIDIA / Caltech / UT Austin
- **年份**：2023

**核心思想**：在 Minecraft 中持续探索的终身学习 Agent，包含三个组件：
1. **Automatic Curriculum**（自动课程生成，最大化探索）
2. **Skill Library**（可执行代码的技能库，按 embedding 索引可检索）
3. **Iterative Prompting**（环境反馈 + 执行错误 + 自验证改进程序）

**关键结论**：
- 获得 3.3× 更多独特物品，技术树解锁快 15.3×
- 技能库可迁移到全新环境解决新任务

**对 Nora 的启示**：前端"AI 能力中心"的自定义技能（OpenAPI Schema）可以演进为 **Skill Library**：每个成功执行的 tool-call 序列存为可检索的技能，未来相似请求直接复用已验证的调用链。

---

### 5. SWE-agent: Designing an Agent-Computer Interface (ACI)

- **作者**：John Yang, Carlos E. Jimenez, Alexander Wettig, Kilian Lieret, Shunyu Yao, Karthik Narasimhan, Ofir Press
- **机构**：Princeton
- **年份**：2024 · arXiv:2405.15793 · NeurIPS 2024

**核心思想**：LLM Agent 是一类新的"最终用户"，需要为它们专门设计接口（类比人类需要 IDE）。论文提出 **Agent-Computer Interface** 概念，并证明：

- 简化但信息密集的搜索结果（截断至 50 行）
- 文件查看器显示 100 行窗口 + 行号 + 上下文提示
- 编辑后立即展示更新后内容
- Linter 集成到编辑函数中，无效编辑直接拒绝并要求重试

**关键结论**：同等模型下，专门设计的 ACI 能显著提升成功率，"interface design matters"。

**对 Nora 的启示**：`NoraTools` 的返回值不应是原始工具输出，而应经过 **为 LLM 设计的压缩与格式化**：SQL 结果截断 + 摘要、日志去噪 + 高亮关键行、错误信息附带修复提示。

---

### 6. LATS: Language Agent Tree Search

- **作者**：Zhouxiang Xie, Dylan S. Kim, Junsol Chang, Xindi Wu, Lang Yu, Bhuwan Dhingra, Daniel Russo
- **机构**：UW-Madison + Columbia + NYU + Microsoft
- **年份**：2023 · arXiv:2310.04406 · ICML 2024

**核心思想**：统一 reasoning + acting + planning，用 MCTS 树搜索替代线性轨迹。失败轨迹的 reflection 作为额外上下文注入后续尝试。

**关键结论**：
- Programming / QA / Web 导航均有显著提升
- 计算成本极高（100-300× CoT）

**对 Nora 的启示**：**不采用**。个人工作台对延迟和成本敏感，线性 ReAct + ReWOO 混合已足够；LATS 留作未来高价值任务的可选模式。

---

## 二、业界实践

### 1. Anthropic: Building Effective Agents（2024-12）

来源：<https://www.anthropic.com/engineering/building-effective-agents>

**核心观点**：

> "The most successful implementations use simple, composable patterns rather than complex frameworks."

**关键概念**：区分 **Workflow**（LLM 按预定义代码路径编排）与 **Agent**（LLM 动态决定自己的流程和工具使用）。

**五个模式（由简到复杂）**：

| 模式 | 适用场景 |
|------|---------|
| Prompt Chaining | 任务可分解为固定子步骤 |
| Routing | 输入分类后走专用 prompt |
| Parallelization | 子任务可并行（sectioning / voting） |
| Orchestrator-Workers | 动态分解任务（类似 Plan-and-Execute） |
| Evaluator-Optimizer | LLM 生成 → LLM 评估 → 循环 |

**对 Nora 的启示**：Nora 大部分场景是 **Workflow 而非 Agent**：
- 文件上传→索引→可检索：固定管线，不该让 LLM 决定流程
- 环境诊断：固定"读日志→诊断→建议"三步
- 只有用户在 `/chat` 提出开放式问题时才进入 Agent 模式

---

### 2. Cognition (Devin): Don't Build Multi-Agents（2025-06）

来源：<https://cognition.com/blog/dont-build-multi-agents>

**核心原则**：
1. **Share full context, not individual messages**——子 agent 要能看到完整决策轨迹，否则会做冲突决策
2. **Actions carry implicit decisions**——两个并行 agent 对同一文件做出不同编辑 = 灾难
3. **Single-threaded > multi-agent**——Claude Code 的 subagent 只用于回答问题，不用于并行写代码

**对 Nora 的启示**：
- **不引入多 agent 编排**（supervisor/swarm），agent-service 内保持单线程 ReAct 循环
- 若未来需要 subagent，只用于"信息检索类"只读任务，不用于会产生副作用的写操作

---

### 3. LangGraph（LangChain）

来源：<https://docs.langchain.com/oss/python/langgraph/checkpointers>

**核心能力**：
- **State Graph**：节点 = 一步操作，边 = 条件分支，共享状态对象
- **Checkpointing**：每个 super-step 保存状态快照 → 支持断点恢复、时间旅行调试
- **Human-in-the-Loop**：在任意节点 interrupt，等待人工批准后继续
- **Thread-based memory**：按 thread_id 存储会话状态

**对 Nora 的启示**：
- Agent 轨迹（steps、tool calls、observations）必须 **逐 step 持久化**，不能只在结束时一次性写入
- 高风险工具（删除文件、执行 DDL）需要 interrupt → 用户批准 → 继续的机制

---

### 4. OpenAI Agents SDK

来源：<https://developers.openai.com/api/docs/guides/agents>

**核心概念**：
- **Agent Loop**：SDK 管理"模型调用 → 工具执行 → 结果回填"循环，直到 run 结束
- **Handoffs**：agent 之间显式转移控制权
- **Guardrails**：输入/输出/工具输入/工具输出四层防护，触发 tripwire 异常立即中断
- **Sessions**：内置会话状态管理
- **Tracing**：跨模型调用、工具、agent 的统一追踪

**对 Nora 的启示**：
- Guardrail 分层设计值得借鉴：`input guardrail`（输入安全）→ `tool input guardrail`（SQL 只读校验）→ `tool output guardrail`（敏感信息脱敏）→ `output guardrail`（最终输出检查）
- Tracing 对应我们的 observability 需求，每个 agent step 都要有 span

---

## 三、汇总：Nora agent-service 设计决策

| 维度 | 决策 | 依据 |
|------|------|------|
| 执行模式 | **ReAct 为基础**（对话场景）+ **ReWOO 优化**（多工具场景） | Yao 2022 / Xu 2023 |
| 多 agent | **不引入**，单线程循环 | Cognition / Anthropic |
| 工具接口 | 为 LLM 专门设计的 ACI（截断/摘要/格式化/校验） | SWE-agent |
| 记忆 | ChatMemory（短期）+ Reflexion episodic（失败反思） | LangChain4j / Shinn 2023 |
| 技能沉淀 | Skill Library（成功调用链存为可检索技能） | Voyager |
| 规划 | Plan-and-Execute 仅用于 >3 步独立任务 | Anthropic / LangChain |
| 安全 | 四层 Guardrail（input/tool-input/tool-output/output） | OpenAI Agents SDK |
| 状态 | 逐 step checkpoint 持久化 | LangGraph |
| 复杂度 | 简单模式优先，不引入 ToT/LATS/MCTS | Anthropic / Cognition |
| 观测 | 每 step 一个 span，全链路 trace | OpenAI / LangGraph |