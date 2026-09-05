# 对话执行时间线 · 2026-09-05

## 目标

让对话页像 Codex Harness 一样呈现可监督的执行过程：用户能看到 Agent 的决策、每个工具调用的输入、运行中状态、输出摘要、耗时和失败原因，并可按步骤折叠查看。

## 事件模型

- `step(running)`：创建执行单元，显示工具名称和输入。
- `step(completed|failed)`：更新同一执行单元，补充输出和耗时。
- `delta`：回答文本增量。
- `sources`：检索来源。
- `done` / `error`：整轮结束或失败。

模型决策与工具调用使用不同的 step id：`s-tool-N` 表示决策轮，`s-tool-N-call-M` 表示该轮的第 M 个工具调用，避免多个工具互相覆盖。

## 展示规则

- 默认展开当前步骤，历史步骤可点击收起。
- 工具详情分为“输入”和“输出”代码块，长输出在卡片内部滚动。
- 运行中显示 spinner，成功显示耗时，失败显示失败标记。
- 上下文用量按当前会话消息和输入实时估算；后续接入网关 `usage` 后替换为供应商真实 token 计量。

## 验证

前端 `typecheck`、`lint`、68 个 Vitest 用例通过；agent-service Maven 测试通过。

## 思考块升级

一条 assistant 消息只保留一个可折叠思考块；最终回答、引用来源和保存按钮放在思考块外。思考块按 `roundIndex` 分组 ReAct 轮次，同一轮中的模型 reasoning 与工具调用连续展示。

成功完成后思考块默认收起；运行中、失败或拦截时默认展开。用户手动展开后，后续状态更新不再改写折叠状态。工具行显示 `toolName + JSON 参数预览 + lines of output + 耗时`，展开后分为“工具参数”和“执行结果”代码块。

最终回答仍由模型生成自然 Markdown；UI 只通过 `.chat-markdown` 做紧凑渲染，收窄段落、列表、表格和代码块间距。结构化块只承载工具参数、原始结果和失败原因，避免前端替模型编造展示层结论。

数据契约：`roundIndex=0` 表示 RAG 检索，`1+` 表示模型工具轮；`result.lineCount` 由后端按工具输出原文统计；`V5__step_round_index.sql` 为 `agent_step` 增加 `round_index`。

## 真实推理模式

前端模型选择器会把 `defaultModel` 作为 `model` 随消息一起提交；后端按“启用且模型列表包含该模型”的服务商解析端点和密钥。`reasoning_content` / `reasoning` / `thinking` 会在非流式工具轮读取，在最终回答流式阶段通过 `reasoning_delta` 事件转发。

前端把同一 `roundIndex` 的 reasoning deltas 聚合为 `s-reasoning-{round}` 思考步骤；收到首个回答 delta 或 `done` 时标记完成。控制器只在整轮结束时把聚合后的 reasoning step 持久化到 `agent_step`，避免每个 token 重复落库。
