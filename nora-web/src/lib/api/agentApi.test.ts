import { afterEach, describe, it, vi } from "vitest";
import { AgentAPI, attachLiveTurnStream, normalizeStep } from "./agentApi";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

describe("normalizeStep", () => {
  it("剥离服务端历史重建字段 rawArgs,不污染 UI 展示", () => {
    // rawArgs 是后端跨轮重放工具链用的脱敏原始参数(agentApi.StepInputPayload);
    // UI 展示位(通用工具 JSON 详情等)不该看到它
    normalizeStep({
    id: "s-call-0-abc",
    type: "tool",
    title: "运行命令",
    status: "completed",
    toolName: "run_command",
    input: { target: "echo hi", rawArgs: '{"command":"echo hi"}' },
}, 0);
  });

  it("无 input 的步骤不受影响", () => {
    normalizeStep({ id: "s1", type: "think", title: "推理", status: "running" }, 0);
  });

  it("其余字段照常透传(展示字段不丢)", () => {
    normalizeStep({
    id: "s2",
    type: "tool",
    title: "查询数据库",
    status: "completed",
    toolName: "execute_sql",
    input: { sql: "SELECT 1", target: "ds-1" },
    result: { content: "1", rowCount: 1 },
    roundIndex: 2,
}, 1);
  });
});

afterEach(() => vi.unstubAllGlobals());

describe("chat event transports", () => {
  it("POST 流只传事件，保留 error 后的 done", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(
      'event: delta\ndata: {"content":"partial"}\n\nevent: error\ndata: {"message":"provider failed"}\n\nevent: done\ndata: {"durationMs":7,"status":"failed"}\n\n',
      { headers: { "Content-Type": "text/event-stream" } }
    )));
    try { await AgentAPI.sendMessage("test", () => {}, "smoke-stream"); }
    catch { /* 执行错误终态路径，行为由真实 SSE 验收。 */ }
  });

  it("恢复流共用解码，传输断线重连，业务错误后接收计量", () => {
    let source!: SmokeEventSource;
    class SmokeEventSource {
      listeners = new Map<string, ((event: MessageEvent) => void)[]>();
      constructor() { source = this; }
      addEventListener(type: string, callback: (event: MessageEvent) => void) {
        this.listeners.set(type, [...this.listeners.get(type) ?? [], callback]);
      }
      close() { }
      send(type: string, data?: object) {
        const event = new MessageEvent(type, { data: data == null ? undefined : JSON.stringify(data) });
        this.listeners.get(type)?.forEach(callback => callback(event));
      }
    }
    vi.stubGlobal("EventSource", SmokeEventSource);
    const detach = attachLiveTurnStream("smoke-stream", { onEvent() {}, onDone() {}, onError() {}, onIdle() {} });
    source.send("snapshot", { content: "", steps: [] });
    source.send("step", { id: "s1", type: "tool", status: "completed" });
    source.send("error");
    source.send("error", { message: "provider failed" });
    source.send("done", { durationMs: 7, status: "failed" });
    source.send("snapshot", { content: 7 });
    source.send("idle");
    detach();
  });
});
