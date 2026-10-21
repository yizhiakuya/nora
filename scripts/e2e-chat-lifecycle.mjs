// 当前 React hook + 真实 HTTPS / SSE；仅主动断开客户端传输，不伪造服务端结果。
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
const dom = new JSDOM("", { url: base, pretendToBeVisual: true });
for (const name of ["window", "document", "localStorage", "sessionStorage", "DOMException"])
  globalThis[name] = name === "window" ? dom.window : dom.window[name];
Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = false;
if (process.env.NORA_AUTH_TOKEN) localStorage.setItem("nora-auth-token", process.env.NORA_AUTH_TOKEN);
const NativeEventSource = globalThis.EventSource;
if (!NativeEventSource) throw new Error("Run Node with --experimental-eventsource");
globalThis.EventSource = class extends NativeEventSource {
  constructor(url) { super(new URL(url, base)); }
};
const http = globalThis.fetch;
let disconnect;
let offline = false;
let delayPost = false;
const requests = [];
globalThis.fetch = async (url, init) => {
  const target = new URL(url, base);
  requests.push({ path: target.pathname, method: init?.method || "GET" });
  if (offline) throw new TypeError("E2E client offline");
  if (delayPost && init?.method === "POST" && target.pathname.endsWith("/messages")) await new Promise(resolve => setTimeout(resolve, 800));
  const response = await http(target, init);
  if (init?.method !== "POST" || !target.pathname.endsWith("/messages") || !response.ok) return response;
  const reader = response.body.getReader();
  let ended = false;
  return new Response(new ReadableStream({
    start(controller) {
      disconnect = () => {
        if (ended) return;
        ended = true;
        controller.error(new TypeError("E2E client connection dropped"));
        void reader.cancel();
      };
    },
    async pull(controller) {
      try {
        const item = await reader.read();
        if (ended) return;
        if (item.done) { ended = true; controller.close(); }
        else controller.enqueue(item.value);
      } catch (error) { if (!ended) { ended = true; controller.error(error); } }
    },
    cancel() { ended = true; return reader.cancel(); },
  }), { status: response.status, headers: response.headers });
};
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nora-lifecycle-"));
let client;
try {
  await build({ stdin: { contents: 'export { useChat } from "@/hooks/useChat"; export { useChatSessions } from "@/hooks/useChatSessions"; export { useModelProviders } from "@/hooks/useModelProviders"; export * from "@/lib/api/agentApi"; export { requestJson } from "@/lib/api/client";', resolveDir: path.join(root, "nora-web"), loader: "ts" },
    bundle: true, platform: "node", format: "cjs", outfile: path.join(directory, "client.cjs"),
    alias: { "@": path.join(root, "nora-web/src"), react: require.resolve("react") }, external: [require.resolve("react")] });
  client = require(path.join(directory, "client.cjs"));
} finally {
  if (path.dirname(path.resolve(directory)) !== path.resolve(os.tmpdir())) throw new Error("Temporary directory escaped");
  fs.rmSync(directory, { recursive: true, force: true });
}
const { renderHook, act, cleanup } = require("@testing-library/react");
const { useChat, useChatSessions, useModelProviders, bindSessionProject, requestJson, fetchChatQueue, fetchLiveTurn, fetchSessionMessages, cancelTurnOnBackend, deleteSessionOnBackend } = client;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, label) => { if (!condition) throw new Error(label); console.log("PASS", label); };
async function until(test, label, timeout = 45000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await test()) return; await pause(100); }
  throw new Error("Timed out: " + label);
}
const id = `lifecycle-e2e-${Date.now()}`;
const prompt = "这是交互验收。只调用一次 ask_user 提问『继续验收？』，等待我的回答，不调用任何其他工具；收到回答后只回复 LIFECYCLE_OK。";
let view;
try {
  await useModelProviders.getState().syncFromBackend();
  const workspace = await requestJson("/workspace");
  await bindSessionProject(id, workspace.root);
  await useChatSessions.getState().loadSession(id);
  useChatSessions.getState().setActive(id);
  view = renderHook(() => useChat({ sessionId: id }));
  await pause(1500);
  act(() => view.result.current.setInput(prompt));
  let sending;
  act(() => { const send = view.result.current.sendMessage; sending = send(); void send(); });
  await until(() => view.result.current.messages.some(m => m.question), "真实模型提问");
  const queue = await fetchChatQueue(id);
  check(queue.messages.length === 0, "快速连点同一输入没有重复排队");
  const token = view.result.current.messages.find(m => m.question).question.questionToken;
  offline = true;
  disconnect();
  await pause(1500);
  offline = false;
  window.dispatchEvent(new dom.window.Event("online"));
  await until(() => view.result.current.isSending && view.result.current.messages.some(m => m.question?.questionToken === token), "断线后自动恢复同一提问", 12000);
  check((await fetchLiveTurn(id)).running, "客户端断线不取消服务端任务");
  view.unmount();
  useChatSessions.setState({ messagesBySession: {} });
  view = renderHook(() => useChat({ sessionId: id }));
  await until(() => view.result.current.isSending && view.result.current.messages.some(m => m.question?.questionToken === token), "重新挂载恢复真实任务");
  check(true, "切页 / 空内存重载恢复同一任务及待回答问题");
  const before = requests.filter(r => r.path.endsWith("/cancel")).length;
  await act(async () => { const stop = view.result.current.stopGenerating; await Promise.all([stop(), stop()]); });
  await until(() => !view.result.current.isSending && !view.result.current.isStopping, "取消终态");
  check(requests.filter(r => r.path.endsWith("/cancel")).length === before + 1, "重复停止只提交一次取消");
  check((await fetchSessionMessages(id, true)).some(m => m.stopped), "停止状态持久化");
  act(() => view.result.current.setInput("这是停止后的新任务。不要调用工具，只回复 RESEND_OK。"));
  await act(async () => { await view.result.current.sendMessage(); });
  await until(() => !view.result.current.isSending && view.result.current.messages.some(m => m.content.includes("RESEND_OK") && m.role === "assistant"), "停止后立即重发完成");
  const history = await fetchSessionMessages(id, true);
  check(history.filter(m => m.role === "user").length === 2, "停止后重发仅产生预期的两轮消息");
  delayPost = true;
  act(() => view.result.current.setInput(prompt));
  let earlySend;
  act(() => { earlySend = view.result.current.sendMessage(); });
  await act(async () => { await view.result.current.stopGenerating(); });
  await until(() => !view.result.current.isSending && !view.result.current.isStopping, "请求仍在传输时立即停止", 12000);
  await earlySend;
  check(!(await fetchLiveTurn(id)).running, "发送请求晚于取消请求到达时仍能停止");
  await sending;
  console.log("COMPLETE", id);
} finally {
  offline = false;
  view?.unmount(); cleanup();
  await cancelTurnOnBackend(id);
  await until(async () => !(await fetchLiveTurn(id)).running, "验收任务清理");
  const queue = await fetchChatQueue(id);
  for (const message of queue.messages) await client.withdrawChatInput(id, message.id);
  await deleteSessionOnBackend(id);
  dom.window.close();
}
process.exit(0);
