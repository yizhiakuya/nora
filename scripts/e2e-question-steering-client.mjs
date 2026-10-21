// 当前客户端回放真实提问/插入事件，核对问题卡与分段状态。
import assert from "node:assert/strict";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(path.join(root, "nora-web/package.json"));
const { build } = createRequire(require.resolve("vite/package.json"))("esbuild");
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nora-question-client-"));
let client;
try {
  await build({ entryPoints: [path.join(root, "nora-web/src/lib/api/chatApi.ts")],
    bundle: true, platform: "node", format: "cjs", outfile: path.join(directory, "chatApi.cjs"),
    alias: { "@": path.join(root, "nora-web/src") } });
  client = require(path.join(directory, "chatApi.cjs"));
} finally {
  assert.ok(directory.startsWith(path.join(os.tmpdir(), "nora-question-client-")));
  fs.rmSync(directory, { recursive: true, force: true });
}
const trace = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
let message = { id: "real-turn", role: "assistant", content: "", steps: [], isTyping: true };
let cleared = 0;
for (const [kind, data] of trace.events) {
  const event = kind === "step" ? { type: kind, step: data }
    : kind === "question_required" ? { type: kind, question: data }
    : kind === "delta" || kind === "reasoning_delta" ? { type: kind, ...data }
    : kind === "done" ? { type: "finish", stopped: data.stopped, turnMetrics: data } : undefined;
  if (!event) continue;
  const previous = message.question;
  message = { ...message, ...client.applyChatStreamEvent(message, event) };
  if (kind === "step" && data.toolName === "ask_user" && data.status === "completed") {
    assert.equal(previous?.stepId, data.id);
    assert.equal(message.question, undefined);
    assert.ok(client.splitChatMessages([message]).every(part => !part.question));
    cleared++;
  }
}
assert.equal(cleared, 2);
assert.equal(message.isTyping, false);
assert.equal(message.question, undefined);
assert.notEqual(trace.before.question.questionToken, trace.after.question.questionToken);
const restored = { ...message, ...client.applyChatStreamEvent(message, { type: "snapshot", message: trace.after }) };
const cards = client.splitChatMessages([restored]).filter(part => part.question);
assert.equal(cards.length, 1);
assert.equal(cards[0].question.questionToken, trace.after.question.questionToken);
console.log("PASS: real SSE clears superseded and answered cards; reconnect restores only the current question");
