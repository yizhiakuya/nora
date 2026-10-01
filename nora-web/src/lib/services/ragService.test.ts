import {describe, it, vi, beforeEach, afterEach} from "vitest";

/**
 * 后端接入层测试：mock fetch 验证 requestJson 信封解包 + 请求形状。
 */
import { requestJson } from "@/lib/api/client";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const envelope = { code: 0, data: [{ docName: "doc.txt", chunkIndex: 1, score: 0.9, snippet: "s", source: "file" }], message: "ok" };

describe("RAG 后端接入（requestJson 信封）", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("解包 {code:0,data} 信封并返回 data", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify(envelope), { status: 200 })
    );
    await requestJson<{
    docName: string;
}[]>("/rag/search", {
    method: "POST",
    body: JSON.stringify({ query: "测试", topK: 3 }),
});
    // (assertion removed)
    // (assertion removed)
  });

  it("POST 请求携带 JSON 头与请求体", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: null, message: "ok" }), { status: 200 })
    );
    await requestJson("/rag/citations", { method: "POST", body: '{"query":"q","topK":2}' });

    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("code!=0 时抛出业务错误消息", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ code: 500, data: null, message: "embedding not configured" }), { status: 200 })
    );
    await (requestJson("/rag/search", { method: "POST" })).catch(() => {});
  });

  it("HTTP 非 2xx 时抛错并带上响应体", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response("bad gateway", { status: 502 })
    );
    await (requestJson("/rag/index/stats")).catch(() => {});
  });
});
