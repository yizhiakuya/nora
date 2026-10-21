// 直连真实 HTTP；仅延迟响应交付来复现同步竞态，不伪造业务响应。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(path.join(root, "nora-web/package.json"));
const { build } = createRequire(require.resolve("vite/package.json"))("esbuild");
const { JSDOM } = require("jsdom");
const base = process.env.NORA_E2E_BASE_URL || "http://127.0.0.1:18080";
const phase = process.env.NORA_DELETE_STRESS_PHASE;
const snapshotPath = process.env.NORA_DELETE_STRESS_SNAPSHOT;
const dom = new JSDOM("", { url: base, pretendToBeVisual: true });
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
for (const key of ["window", "document", "localStorage", "sessionStorage"]) globalThis[key] = key === "window" ? dom.window : dom.window[key];
if (process.env.NORA_AUTH_TOKEN) localStorage.setItem("nora-auth-token", process.env.NORA_AUTH_TOKEN);
if (snapshotPath) {
  if (fs.existsSync(snapshotPath)) localStorage.setItem("chat-sessions", fs.readFileSync(snapshotPath, "utf8"));
  const save = dom.window.Storage.prototype.setItem;
  dom.window.Storage.prototype.setItem = function (key, value) {
    save.call(this, key, value);
    if (key === "chat-sessions") fs.writeFileSync(snapshotPath, value);
  };
}
const http = globalThis.fetch;
let intercept;
globalThis.fetch = async (url, init) => {
  const response = await http(new URL(url, base), init);
  if (intercept) await intercept(new URL(url, base), init);
  return response;
};
const schedule = globalThis.setTimeout;
globalThis.setTimeout = (callback, ms, ...args) => {
  const timer = schedule(callback, ms, ...args);
  if (ms >= 30000) timer.unref();
  return timer;
};
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "nora-session-delete-"));
let client;
try {
  await build({ stdin: { contents: 'export { useChatSessions } from "@/hooks/useChatSessions"; export * from "@/lib/api/agentApi"; export { requestJson } from "@/lib/api/client";', resolveDir: path.join(root, "nora-web"), loader: "ts" },
    bundle: true, platform: "node", format: "cjs", outfile: path.join(dir, "client.cjs"), alias: { "@": path.join(root, "nora-web/src") } });
  client = require(path.join(dir, "client.cjs"));
} finally {
  if (path.dirname(path.resolve(dir)) !== path.resolve(os.tmpdir())) throw new Error("临时目录越界");
  fs.rmSync(dir, { recursive: true, force: true });
}
const { useChatSessions, bindSessionProject, fetchSession, deleteSessionOnBackend, requestJson } = client;
const state = () => useChatSessions.getState();
const ids = [];
let passed = 0;
const check = (ok, label) => { if (!ok) throw new Error(label); process.stdout.write(`PASS ${++passed}. ${label}\n`); };
const absent = id => !state().sessions.some(row => row.id === id);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
if (phase) {
  const stressIds = JSON.parse(process.env.NORA_DELETE_STRESS_IDS);
  if (!stressIds.length || stressIds.some(id => !id.startsWith("stress-"))) throw new Error("Only explicit test sessions may be deleted");
  await state().syncFromBackend();
  if (phase === "delete") {
    const start = Date.now();
    for (const id of stressIds) {
      check(state().deleteSession(id), `开始删除 ${id}`);
      check(!state().deleteSession(id), `连续重复点击不重复登记 ${id}`);
    }
    check(stressIds.every(absent), "连续删除后所有测试项立即隐藏");
    process.stdout.write(`DELETE_BATCH_MS ${Date.now() - start}\n`);
  } else if (phase === "restart") {
    check(stressIds.every(absent), "新进程恢复快照并同步后无测试会话复活");
    await delay(80);
  } else if (phase === "finish") {
    const deadline = Date.now() + 12000;
    while (stressIds.some(id => state().pendingDeletions[id]) && Date.now() < deadline) await delay(50);
    check(stressIds.every(id => !state().pendingDeletions[id]), "所有跨进程待删除操作均已提交");
    await state().syncFromBackend();
    check(stressIds.every(absent), "重新同步后无残留索引");
    for (const id of stressIds) {
      let missing = false;
      try { await fetchSession(id); } catch (error) { missing = error.code === 404; }
      check(missing, `服务端确认已删除 ${id}`);
    }
  } else throw new Error("Unknown stress phase");
  process.stdout.write(JSON.stringify({ phase, pending: Object.keys(state().pendingDeletions), at: Date.now() }) + "\n");
  // 退出整个运行环境，丢弃旧定时器；下一进程只能从持久化快照恢复。
  dom.window.close();
  process.exit(0);
}
let release;
async function hold(pathname, action) {
  let arrived;
  const ready = new Promise(resolve => { arrived = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  intercept = async (url, init) => {
    if (url.pathname !== pathname || (init?.method && init.method !== "GET")) return;
    intercept = undefined;
    arrived();
    await gate;
  };
  const pending = action();
  await ready;
  return { pending };
}
try {
  const workspace = await requestJson("/workspace");
  const id = `deletion-e2e-${Date.now()}`;
  ids.push(id);
  await bindSessionProject(id, workspace.root);
  await state().loadSession(id);
  state().setActive(id);
  const before = await hold("/api/chat/sessions", () => state().syncFromBackend());
  check(state().deleteSession(id), "删除真实会话进入撤销窗口");
  release(); await before.pending;
  check(absent(id) && state().activeId !== id, "删除前发出的迟到列表不会恢复当前会话");
  await state().syncFromBackend();
  check(absent(id), "撤销窗口内服务器仍有记录也不恢复列表项");
  check(state().undoDeleteSession(id) && !absent(id), "五秒内撤销恢复本地快照");
  check((await fetchSession(id)).id === id, "撤销保留服务端记录");
  const detail = await hold(`/api/chat/sessions/${id}`, () => state().loadSession(id));
  state().deleteSession(id);
  await state().commitDeleteSession(id);
  release(); await detail.pending;
  check(absent(id) && !state().pendingDeletions[id], "提交后迟到详情不会复活已删除记录");
  await deleteSessionOnBackend(id);
  let missing = false;
  try { await fetchSession(id); } catch (error) { missing = error.code === 404; }
  check(missing, "重复 DELETE 成功且详情明确返回 404");
  await state().syncFromBackend();
  check(absent(id), "提交后重新同步没有幽灵会话");
  useChatSessions.setState({ sessions: [...state().sessions, { id, title: "stale", updatedAt: Date.now() }], activeId: id });
  try { await state().loadSession(id); } catch (error) { if (error.code !== 404) throw error; }
  check(absent(id) && state().activeId !== id, "失效详情清理历史本地索引及选中状态");
  const draft = state().createSession();
  ids.push(draft);
  state().deleteSession(draft);
  await useChatSessions.persist.rehydrate();
  await delay(5500);
  check(!state().pendingDeletions[draft] && absent(draft), "未落库草稿刷新后仍自动完成删除");
  const refresh = `deletion-refresh-${Date.now()}`;
  ids.push(refresh);
  await bindSessionProject(refresh, workspace.root);
  await state().loadSession(refresh);
  state().deleteSession(refresh);
  await useChatSessions.persist.rehydrate();
  await delay(5500);
  missing = false;
  try { await fetchSession(refresh); } catch (error) { missing = error.code === 404; }
  check(missing && !state().pendingDeletions[refresh], "已落库会话刷新后仍自动完成删除");
  process.stdout.write(`完成：${passed} 项真实 HTTP 检查通过\n`);
} finally {
  release?.(); intercept = undefined;
  for (const id of ids) {
    state().undoDeleteSession(id);
    await deleteSessionOnBackend(id);
  }
  dom.window.close();
}
