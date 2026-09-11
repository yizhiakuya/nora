# 上下文管理系统性设计（2026-09-08）

## 目标

单文件 `buildMessages` 的字符裁剪升级为贯穿「单轮对话全生命周期」的上下文预算体系：度量统一、分层预算、多级防线、计量闭环。设计对齐 `harness-tool-calling-research-2026-09-05.md` 的调研结论（Claude Code microcompact / 83% 触发线 / Manus 上下文原则）。

## 架构：一个度量核心 × 三个应用层 × 三级防线

```
ContextBudget (度量核心)
  ├─ 窗口来源：设置页 per-model contextWindow（ResolvedLlm.contextWindow），未配置 → 128k 默认
  ├─ 触发线 = 窗口 × 83%          （对齐 Claude Code：200K@167K 触发 compact）
  ├─ 输出预留 = min(16k, 窗口×10%) （留给本轮回答 + 推理 token）
  ├─ 恢复线 = 窗口 × 60%          （超限后的压缩目标）
  └─ estimateTokens(): CJK 感知估算（中文≈1 token/字，ASCII≈1/4 字符，其他≈1/2）
       唯一估算口径——历史裁剪、轮内压缩、prompt 估算共用；宁高勿低。
```

### 应用层

| 层 | 位置 | 机制 |
|---|---|---|
| 会话历史装配 | `buildMessages` | 固定层（system+RAG+反思+新消息）之外的 token 预算 = 触发线 − 输出预留 − 固定开销；历史从新到旧装入，至少 1 条，硬上限 40 条；**工具链按 wire 结构重建**（见下） |
| 轮内微压缩 | `compactForRound` | 每轮请求前预估 prompt；超触发线把**最旧的工具结果**就地改写为头 800+尾 200 摘录（失败只留头 400）。只改 content，role/tool_call_id 不动（OpenAI 配对校验不破）；最近 6 条消息永不触碰 |
| 超限恢复 | `isContextOverflow` + force 压缩 | 上游报 context length 错误时硬压缩到恢复线（60%）重试一次——防线上 400 翻车 |

### 工具链历史重建（2026-09-12）

**问题**：跨轮历史只装配 `content` 纯文本，工具调用结构被剥离——模型看到的是「自己声称跑过命令」而不是真实的 `tool_calls + tool result`。实测（deepseek-v4.1-flash）连续编造工具结果/报错（含虚构「sandbox 拦截」），因为历史里「文本即可声称结果」的模式被续写。

**对齐**：Claude Code / Codex 均把工具调用+结果作为一等公民全量重放（「回填即历史」），压缩只截内容不拆结构（Manus：「擦掉失败就擦掉了证据」）。

**实现**：
- 工具步骤持久化脱敏后的原始参数 `StepInput.rawArgs`（`scrubArgsForLog` 同口径；非法 JSON / 超 20K 不附；旧数据 null 优雅退化为纯文本）
- `appendHistoryMessage` 按 `roundIndex` 分组重放为 `assistant(tool_calls) + tool(result)` 对，终态回答文本附在最后；declined/failed 无 content 时用 error 文本回填
- 预算循环 `wireCostOf` 把重建后的工具链计入 token 估算（与 `messageTokens` 同口径）
- Responses 协议路径（`function_call`/`function_call_output`）天然兼容同一重建结构

### 计量闭环

- 服务端：`ChatTurn` 新增 `contextWindow` + `promptTokens`（最后一次请求的估算值），随 `done` 事件下发。
- 前端进度条三级来源（不混计，高级可用就完全不用低级）：
  1. 服务端 prompt 估算（与裁剪同口径，最准）
  2. 逐轮累计真实 usage（全部 assistant 轮都有时）
  3. 字符 ÷4 兜底
- 窗口展示跟随服务端 `contextWindow`，不再前端写死 128k。

## 边界与不变式

- **append-only**：压缩只改写旧 tool content，不删除/重排消息（Manus：擦掉失败就擦掉了证据；KV-cache 友好）。
- **审批/循环熔断不受影响**：压缩发生在 fingerprint 记账之外。
- **usage 真实值**只做展示，不反向参与装配决策（估算与计量分离）。
- 未配置窗口的模型：128k 默认窗口，全链路行为与配置过的模型一致。

## 估算校准闭环(2026-09-08 实测)

- 每轮结束输出校准日志 `context estimate calibration`(估算 vs 上游真实 inputTokens,±35% 外 WARN)。
- 首轮实测 ratio 6.7~10.4:固定 overhead(tools spec ~1.5k + 协议封装)主导,估算严重偏低——真实 token 先于触发线到达,压缩来不及。
- 修正:估算统一计入 `PER_REQUEST_OVERHEAD_TOKENS = 1800`(历史预算、压缩阈值、promptTokens 下发同口径)。
- 修正后实测:估算 2041 vs 真实 1613,ratio 1.26,落入正常带。

## 已知取舍

- 估算是启发式不是 tokenizer：对代码/JSON 密集内容偏差 ±30%，靠 17% 触发线余量 + 超限恢复兜底。
- 微压缩只针对 tool 结果（最大冗余源）；历史 user/assistant 文本已在会话级裁剪中限量。
- 未做全量摘要（auto-compact）：当前会话规模不需要，接入点已预留（`compactForRound` 的 force 分支可替换为摘要实现）。
