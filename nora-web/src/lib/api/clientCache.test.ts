import { describe, it, expect } from "vitest";
import { ttlFor } from "./client";

/**
 * TTL 选择规则的边界测试。
 *
 * 这段逻辑的错误是「静默」的——匹配错了不会报错，只会悄悄缓存不该缓存的数据
 * （比如把文件正文缓存进内存），或者该缓存的不缓存（收益归零）。所以把边界显式钉住。
 */
describe("ttlFor：缓存范围与排除规则", () => {
  it("会话相关：列表与历史都缓存（写操作会失效）", () => {
    expect(ttlFor("/chat/sessions")).toBe(30_000);
    expect(ttlFor("/chat/sessions/sess-123/messages")).toBe(30_000);
    expect(ttlFor("/chat/settings")).toBe(30_000);
  });

  it("配置类资源缓存 60s", () => {
    for (const p of ["/models/providers", "/mcp/servers", "/skills", "/datasources", "/automations"]) {
      expect(ttlFor(p)).toBe(60_000);
    }
  });

  it("查询串不影响判定（同资源同 TTL）", () => {
    expect(ttlFor("/skills?enabled=true")).toBe(60_000);
    expect(ttlFor("/chat/sessions?limit=50")).toBe(30_000);
  });

  it("文件列表缓存，但文件内容（preview 等子路径）绝不缓存", () => {
    expect(ttlFor("/files")).toBe(30_000);
    // 关键：这些返回的是内容或触发副作用，缓存会占内存 / 掩盖新状态
    expect(ttlFor("/files/12/preview")).toBe(0);
    expect(ttlFor("/files/12/index")).toBe(0);
    expect(ttlFor("/files/upload")).toBe(0);
  });

  it("健康检查不缓存（实时性要紧）", () => {
    expect(ttlFor("/chat/health")).toBe(0);
  });

  it("前缀不越界：/chat 规则不命中 /chatx", () => {
    expect(ttlFor("/chatx/thing")).toBe(0);
    expect(ttlFor("/skillssomething")).toBe(0);
  });

  it("未列出的资源默认不缓存", () => {
    expect(ttlFor("/unknown/endpoint")).toBe(0);
    expect(ttlFor("/network/status")).toBe(0);
  });
});
