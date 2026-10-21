import { afterEach, describe, it, vi } from "vitest";
import { requestJson } from "./client";
import { invalidateAll } from "./requestCache";

afterEach(() => { vi.unstubAllGlobals(); invalidateAll(); });

describe("请求头与错误冒烟", () => {
  it("支持 Headers、元组与对象请求头", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: 0, data: [] }))));
    for (const headers of [new Headers({ "X-Test": "headers" }), [["X-Test", "tuple"]] as [string, string][], { "X-Test": "object" }]) {
      await requestJson("/automations", { headers });
    }
  });

  it("错误信封与非 JSON 错误仍向调用方抛出", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 400, data: null, message: "invalid" }), { status: 400 }))
      .mockResolvedValueOnce(new Response("gateway error", { status: 502 })));
    await Promise.allSettled([requestJson("/a"), requestJson("/b")]);
  });
});
