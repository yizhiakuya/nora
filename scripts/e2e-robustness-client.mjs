// 当前前端 store/服务层直连真实 HTTP；不启动或控制浏览器，不模拟业务结果。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(path.join(root, "nora-web/package.json"));
const viteRequire = createRequire(require.resolve("vite/package.json"));
const { build } = viteRequire("esbuild");
const { JSDOM } = require("jsdom");
const base = process.env.NORA_E2E_BASE_URL || "http://127.0.0.1:18080";
const dom = new JSDOM("", { url: base });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
globalThis.sessionStorage = dom.window.sessionStorage;
if (process.env.NORA_AUTH_TOKEN) localStorage.setItem("nora-auth-token", process.env.NORA_AUTH_TOKEN);
const fetchHttp = globalThis.fetch;
globalThis.fetch = (url, init) => fetchHttp(new URL(url, base), init);
// 浏览器请求超时计时器不应单独维持 CLI 进程；真实 HTTP 请求仍等待完成。
const schedule = globalThis.setTimeout;
globalThis.setTimeout = (callback, ms, ...args) => {
  const timer = schedule(callback, ms, ...args);
  if (ms >= 30000) timer.unref();
  return timer;
};
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nora-robustness-"));
let client;
try {
  await build({ stdin: { contents: "export { useAutomations } from \"@/hooks/useAutomations.ts\";\nexport { automationsApi } from \"@/lib/services/automationsApi.ts\";\nexport { requestJson, ttlFor } from \"@/lib/api/client.ts\";\nexport { deleteSessionOnBackend } from \"@/lib/api/agentApi.ts\";\nexport { savedArtifactsApi } from \"@/lib/services/savedArtifactsApi.ts\";", resolveDir: path.join(root, "nora-web"), loader: "ts" },
    bundle: true, platform: "node", format: "cjs", outfile: path.join(directory, "client.cjs"),
    alias: { "@": path.join(root, "nora-web/src") } });
  client = require(path.join(directory, "client.cjs"));
} finally {
  // 仅清理由 mkdtemp 创建的本次编译目录。
  if (path.dirname(path.resolve(directory)) !== path.resolve(os.tmpdir())) throw new Error("临时目录越界");
  fs.rmSync(directory, { recursive: true, force: true });
}
const { useAutomations, automationsApi, requestJson, ttlFor, deleteSessionOnBackend, savedArtifactsApi } = client;
const report = text => process.stdout.write(text);

let id, releaseRead, pendingRead, releaseRun, pendingRun;
const originalList = automationsApi.listRules;
const originalRun = automationsApi.runRule;
let passed = 0;
const check = (condition, label) => {
  if (!condition) throw new Error(label);
  report(`PASS ${++passed}. ${label}\n`);
};
const state = () => useAutomations.getState();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
try {
  await state().syncFromBackend();
  if (state().syncError) throw new Error(state().syncError);
  check(true, "真实规则和历史同步成功");
  const failed = await state().addRule("", "manual", { actionType: "agent", prompt: "确认" });
  check(failed === null && state().rules.every(rule => rule.id < 1e12), "创建失败不插入临时规则");
  const rule = await state().addRule(`robustness-${Date.now()}`, "manual", { actionType: "agent", prompt: "这是程序验收，仅回复 NORA_ROBUSTNESS_OK，不调用工具。" });
  if (!rule) throw new Error("真实任务创建失败");
  id = rule.id;
  check(state().rules.filter(row => row.id === id).length === 1, "服务端规则只入列一次");

  // 只延迟真实 HTTP 快照交付，不伪造接口返回。
  let captured;
  const ready = new Promise(resolve => { captured = resolve; });
  automationsApi.listRules = async () => {
    let rows;
    try { rows = await originalList(); }
    finally { captured(); }
    await new Promise(resolve => { releaseRead = resolve; });
    return rows;
  };
  pendingRead = state().syncFromBackend();
  await ready;
  const toggles = await Promise.all([state().toggleRule(id), state().toggleRule(id)]);
  check(toggles[0] && !toggles[1], "同一规则重复启停被拦截");
  releaseRead();
  await pendingRead;
  automationsApi.listRules = originalList;
  const current = state().rules.find(row => row.id === id);
  check(current.enabled === false && current.status === "paused", "迟到旧列表不能覆盖已暂停的服务端结果");
  const server = (await automationsApi.listRules()).find(row => row.id === id);
  check(server.enabled === current.enabled, "界面状态与服务端一致");

  let calls = 0;
  let readyRun;
  const delivered = new Promise(resolve => { readyRun = resolve; });
  automationsApi.runRule = async ruleId => {
    calls++;
    let result;
    try { result = await originalRun(ruleId); }
    finally { readyRun(); }
    await new Promise(resolve => { releaseRun = resolve; });
    return result;
  };
  pendingRun = state().markRun(id);
  const duplicate = await state().markRun(id);
  check(!duplicate && state().runningRuleIds.includes(id) && calls === 1, "重复运行不发第二个请求");
  await delivered;
  await delay(3300);
  check(state().runningRuleIds.includes(id), "超过三秒仍等待真实响应交付");
  releaseRun();
  const ok = await pendingRun;
  automationsApi.runRule = originalRun;
  check(ok && !state().runningRuleIds.includes(id), "真实完成后解除等待");
  await state().syncFromBackend();
  const first = state().executions.find(row => row.ruleId === id);
  check(first?.status === "success" && first.detail.includes("NORA_ROBUSTNESS_OK"), "真实模型结果已写入执行历史");
  const retry = state().retryExecution(first.id);
  check(state().runningRuleIds.includes(id), "历史重试复用运行状态");
  check(await retry, "历史重试真实执行成功");
  await state().syncFromBackend();
  check(state().executions.filter(row => row.ruleId === id).length === 2, "两次运行只产生两条执行记录");
  const persisted = JSON.parse(localStorage.getItem("automations")).state;
  check(!("runningRuleIds" in persisted) && !("togglingRuleIds" in persisted), "等待状态不写入持久化缓存");
  check(ttlFor("/automations") === 0 && ttlFor("/automations/executions?limit=50") === 0, "任务规则与执行记录无过期 TTL 快照");
  for (const headers of [new Headers({ "Content-Type": "text/plain" }), [["Content-Type", "text/plain"]]]) {
    let rejected = false;
    try { await requestJson("/automations", { method: "POST", headers, body: "{}" }); }
    catch (error) { rejected = /415|text\/plain|not supported/i.test(error.message) || error.code === 415; }
    check(rejected, "自定义 Headers/元组 Content-Type 确实到达真实后端");
  }
  let registrationFailed = false;
  try { await savedArtifactsApi.register({ kind: "knowledge_doc", path: "", name: "invalid-regression" }); }
  catch (error) { registrationFailed = error instanceof Error && error.message.length > 0; }
  check(registrationFailed, "成果登记真实错误向调用方传递");
  report(`完成：${passed} 项通过\n`);
} catch (error) {
  report(`FAIL ${error.stack ?? error}\n`);
  process.exitCode = 1;
} finally {
  releaseRead?.(); releaseRun?.();
  await Promise.allSettled([pendingRead, pendingRun]);
  automationsApi.listRules = originalList;
  automationsApi.runRule = originalRun;
  if (id != null) {
    const cleaned = await Promise.allSettled([automationsApi.deleteRule(id), deleteSessionOnBackend(`auto-rule-${id}`)]);
    if (cleaned.some(value => value.status === "rejected")) process.exitCode = 1;
    report(cleaned.every(value => value.status === "fulfilled") ? "临时规则与会话已清理\n" : "清理失败，请检查临时规则和会话\n");
  }
  await state().syncFromBackend();
}

dom.window.close();
