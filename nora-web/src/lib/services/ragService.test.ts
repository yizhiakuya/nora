import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

/**
 * 后端接入层测试：mock fetch 验证 requestJson 信封解包 + 请求形状。
 * USE_BACKEND 为编译期常量（import.meta.env），无法在单测内切换，
 * 因此直接测 requestJson 路径的请求构造与解包行为。
 */
import { requestJson } from "@/lib/api/client";

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
    const data = await requestJson<{ docName: string }[]>("/rag/search", {
      method: "POST",
      body: JSON.stringify({ query: "测试", topK: 3 }),
    });
    expect(data).toEqual(envelope.data);
    expect(data[0].docName).toBe("doc.txt");
  });

  it("POST 请求携带 JSON 头与请求体", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: null, message: "ok" }), { status: 200 })
    );
    await requestJson("/rag/citations", { method: "POST", body: '{"query":"q","topK":2}' });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/rag/citations");
    expect(init.method).toBe("POST");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe('{"query":"q","topK":2}');
  });

  it("code!=0 时抛出业务错误消息", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ code: 500, data: null, message: "embedding not configured" }), { status: 200 })
    );
    await expect(requestJson("/rag/search", { method: "POST" })).rejects.toThrow("embedding not configured");
  });

  it("HTTP 非 2xx 时抛错并带上响应体", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response("bad gateway", { status: 502 })
    );
    await expect(requestJson("/rag/index/stats")).rejects.toThrow("bad gateway");
  });
});
