// 回放真实模型 SSE 记录；当前客户端代码自动构建到临时目录。
import assert from "node:assert/strict";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(path.join(root, "nora-web/package.json"));
const viteRequire = createRequire(require.resolve("vite/package.json"));
const { build } = viteRequire("esbuild");
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nora-live-client-"));
let client;
try {
  await build({ entryPoints: [path.join(root, "nora-web/src/lib/api/chatApi.ts")],
    bundle: true, platform: "node", format: "cjs", outfile: path.join(directory, "chatApi.cjs"),
    alias: { "@": path.join(root, "nora-web/src") } });
  client = require(path.join(directory, "chatApi.cjs"));
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
const { applyChatStreamEvent, createChatStreamBuffer } = client;
const trace = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
function normalize({ event, data }) {
  if (event === "delta" || event === "reasoning_delta") return { type: event, ...data };
  if (event === "step") return { type: event, step: data };
  if (event === "context_usage") return { type: event, contextUsage: data };
  if (event === "sources") return { type: event, sources: data };
  if (event === "question_required") return { type: event, question: data };
  if (event === "approval_required") return { type: event, approval: data };
  if (event === "done") return { type: "finish", stopped: data.stopped, turnMetrics: data };
}
const events = trace.events.map(normalize).filter(Boolean);
let direct = { content: "", steps: [] };
let buffered = { content: "", steps: [] };
let updates = 0;
const buffer = createChatStreamBuffer(event => {
  updates++;
  buffered = { ...buffered, ...applyChatStreamEvent(buffered, event) };
});
for (let index = 0; index < events.length; index++) {
  const event = events[index];
  direct = { ...direct, ...applyChatStreamEvent(direct, event) };
  buffer.push(event);
  // 将同一段真实输出分散到多个刷新周期，验证定时刷新而非仅最终 flush。
  if (index % 512 === 511) await new Promise(resolve => setTimeout(resolve, 55));
}
buffer.flush();
assert.deepEqual(buffered, direct, "批处理与逐事件更新完全一致，包括步骤顺序、提问和计量");
assert.ok(events.length > 8000, "来源为超窗口的真实长输出记录");
assert.ok(updates < events.length / 10, "文本合并大幅减少状态更新");
let recovered = { content: "", steps: [] };
recovered = { ...recovered, ...applyChatStreamEvent(recovered, { type: "snapshot", message: trace.snapshot.data }) };
let waiting = { content: "", steps: [] };
for (const event of events) {
  waiting = { ...waiting, ...applyChatStreamEvent(waiting, event) };
  if (event.type === "question_required") break;
}
assert.equal(recovered.question.questionToken, waiting.question.questionToken, "完整快照恢复同一个待回答问题");
assert.deepEqual(recovered.steps.map(step => step.id), waiting.steps.map(step => step.id), "快照与原始流步骤顺序相同");
const resumed = trace.resumed.map(normalize).filter(Boolean);
for (const event of resumed) recovered = { ...recovered, ...applyChatStreamEvent(recovered, event) };
assert.equal(recovered.content.trim(), "LIVE_RECOVERY_DONE", "增量续传保留唯一最终回答");
assert.equal(recovered.question, undefined, "回答成功后清除待回答状态");
console.log("PASS", events.length, "真实事件 →", updates, "次状态更新；完整快照与增量终态一致");
