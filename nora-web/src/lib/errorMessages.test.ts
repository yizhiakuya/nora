import { describe, it, expect } from "vitest";
import { humanizeError } from "./errorMessages";

describe("humanizeError", () => {
  it("网络不可达 → network", () => {
    const r = humanizeError("Cannot connect to agent-service");
    expect(r.kind).toBe("network");
    expect(r.message).not.toContain("agent-service");
  });

  it("上游 400 免费渠道限制 → auth 类(截图实测案例)", () => {
    const r = humanizeError("上游 400: Error from provider: Concord's free tier can only be used with OpenCode");
    expect(r.kind).toBe("auth");
    expect(r.message).toContain("模型服务商");
    expect(r.hint).toContain("模型管理");
    expect(r.raw).toContain("free tier");
  });

  it("429 → rate-limit", () => {
    expect(humanizeError("HTTP 429").kind).toBe("rate-limit");
    expect(humanizeError("rate limit exceeded").kind).toBe("rate-limit");
  });

  it("5xx → provider", () => {
    const r = humanizeError("HTTP 502 Bad Gateway");
    expect(r.kind).toBe("provider");
  });

  it("超时 → timeout", () => {
    expect(humanizeError("upstream timed out").kind).toBe("timeout");
  });

  it("未知错误兜底:保留原文且文案不是原始串", () => {
    const r = humanizeError("某个完全没见过的错误");
    expect(r.kind).toBe("unknown");
    expect(r.message).not.toBe("某个完全没见过的错误");
    expect(r.raw).toBe("某个完全没见过的错误");
  });
});
