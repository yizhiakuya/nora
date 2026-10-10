// 回放真实服务 SSE 与历史响应，使用当前前端模块核对显示口径。
import assert from "node:assert/strict";
import fs from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { applyChatStreamEvent } = require("../logs/context-token-client/api/chatApi.cjs");
const { estimateChatContextTokens } = require("../logs/context-token-client/chatContext.cjs");
const { estimateTextTokens, formatTokenCount } = require("../logs/context-token-client/tokenEstimate.cjs");
const filename = process.argv[2];
assert.ok(filename, "提供 e2e-context-meter.py 产生的真实 trace");
const traces = JSON.parse(fs.readFileSync(filename, "utf8"));
const step = value => ({ ...value, inputTokens: value.input?.rawArgs == null ? undefined : estimateTextTokens(value.input.rawArgs) });
let measured = 0;
let growing = 0;
for (const trace of traces) {
  let message = { id: "assistant", role: "assistant", content: "", timestamp: "", steps: [] };
  for (const { event, data } of trace.events) {
    let update;
    if (event === "context_usage") update = { type: event, contextUsage: data };
    else if (event === "step") update = { type: event, step: step(data) };
    else if (event === "delta" || event === "reasoning_delta") update = { type: event, ...data };
    else if (event === "done") update = { type: "finish", stopped: data.stopped, turnMetrics: data };
    else continue;
    const before = estimateChatContextTokens([message]);
    message = { ...message, ...applyChatStreamEvent(message, update) };
    const after = estimateChatContextTokens([message]);
    if (event === "context_usage" && data.source === "upstream" && data.outputTokens != null) {
      assert.equal(after, data.promptTokens + data.outputTokens, `${trace.label} 当前输入+输出；不重复添加工具参数/推理`);
      measured++;
    }
    if (event === "delta" || event === "reasoning_delta") {
      if (after > before) growing++;
    }
  }
  const history = { ...trace.history, id: "history", steps: trace.history.steps.map(step), turnMetrics: trace.history };
  assert.equal(estimateChatContextTokens([message]), estimateChatContextTokens([history]), `${trace.label} 流与刷新历史的显示一致`);
  console.log("PASS", trace.label, "上下文", estimateChatContextTokens([message]));
}
assert.ok(measured >= 5, "已核对至少五次真实请求的完整计量");
assert.ok(growing > 0, "生成过程中计量随真实文本增长");
assert.equal(formatTokenCount(1100), "1.1K");
console.log("PASS", measured, "次真实请求，", growing, "次生成增量");
