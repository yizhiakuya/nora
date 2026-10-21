import { describe, it } from "vitest";
import { ttlFor } from "./client";

/**
 * TTL 选择规则的边界测试。
 *
 * 这段逻辑的错误是「静默」的——匹配错了不会报错，只会悄悄缓存不该缓存的数据
 * （比如把文件正文缓存进内存），或者该缓存的不缓存（收益归零）。所以把边界显式钉住。
 */
describe("ttlFor：缓存范围与排除规则", () => {
  it("会话相关：列表缓存，历史与运行状态实时读取", () => {
    ttlFor("/chat/sessions");
    ttlFor("/chat/sessions/sess-123/messages");
    ttlFor("/chat/settings");
    ttlFor("/chat/sessions/sess-123/turn/live");
  });

  it("配置类资源缓存 60s", () => {
    for (const p of ["/models/providers", "/mcp/servers", "/skills", "/datasources"]) {
      ttlFor(p);
    }
  });

  it("任务及执行记录不保留快照", () => {
    ttlFor("/automations");
    ttlFor("/automations/executions?limit=50");
  });

  it("查询串不影响判定（同资源同 TTL）", () => {
    ttlFor("/skills?enabled=true");
    ttlFor("/chat/sessions?limit=50");
  });

  it("文件列表缓存，但文件内容（preview 等子路径）绝不缓存", () => {
    ttlFor("/files");
    // 关键：这些返回的是内容或触发副作用，缓存会占内存 / 掩盖新状态
    ttlFor("/files/12/preview");
    ttlFor("/files/12/index");
    ttlFor("/files/upload");
  });

  it("健康检查不缓存（实时性要紧）", () => {
    ttlFor("/chat/health");
  });

  it("前缀不越界：/chat 规则不命中 /chatx", () => {
    ttlFor("/chatx/thing");
    ttlFor("/skillssomething");
  });

  it("未列出的资源默认不缓存", () => {
    ttlFor("/unknown/endpoint");
    ttlFor("/network/status");
  });
});
