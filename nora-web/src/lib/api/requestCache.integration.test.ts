import { describe, it, expect, beforeEach } from "vitest";
import { cached, invalidateForPath, invalidateAll, resetStats } from "./requestCache";

/**
 * 端到端行为验证：模拟真实前端调用序列，断言「后端实际被调用几次」。
 *
 * 与 requestCache.test.ts 的分工：那边测单点边界，这里测**真实调用序列下的
 * 总量收敛**——用真实日志里观察到的高频重复（会话列表并发拉、历史反复拉）
 * 作为输入，验证这层确实把重复消掉了，而不是只是「有缓存代码」。
 */
describe("requestCache 真实调用序列", () => {
  let backendCalls: number;
  const backend = async (tag: string) => {
    backendCalls++;
    await new Promise((r) => setTimeout(r, 10)); // 模拟网络往返
    return tag;
  };

  beforeEach(() => {
    invalidateAll();
    resetStats();
    backendCalls = 0;
  });

  it("切会话时 6 次并发拉会话列表 → 后端只收到 1 次", async () => {
    const results = await Promise.all(
      Array.from({ length: 6 }, () =>
        cached("/chat/sessions", 30_000, false, () => backend("list"))),
    );

    expect(backendCalls).toBe(1);
    // 所有调用方拿到同一份数据（不是各自一份）
    expect(new Set(results).size).toBe(1);
  });

  it("反复切换会话历史 → 每个会话只拉一次", async () => {
    for (const sid of ["a", "b", "c"]) {
      await cached(`/chat/sessions/${sid}/messages`, 30_000, false, () => backend(`msg-${sid}`));
    }
    expect(backendCalls).toBe(3);

    // 回访（真实场景：切走再切回来）
    for (const sid of ["a", "b", "c", "a", "b"]) {
      await cached(`/chat/sessions/${sid}/messages`, 30_000, false, () => backend(`msg-${sid}`));
    }
    expect(backendCalls).toBe(3); // 未新增
  });

  it("发消息（写）后重新拉取 → 看到新数据", async () => {
    await cached("/chat/sessions", 30_000, false, () => backend("v1"));
    await cached("/chat/sessions", 30_000, false, () => backend("v2"));
    expect(backendCalls).toBe(1);

    invalidateForPath("/chat/sessions"); // 发送消息成功后触发
    const after = await cached("/chat/sessions", 30_000, false, () => backend("v2"));

    expect(backendCalls).toBe(2);
    expect(after).toBe("v2"); // 拿到的是写后的值
  });

  it("AI 标题轮询 force 读 → 每次都能看到后端最新值", async () => {
    await cached("/chat/sessions", 30_000, false, () => backend("t0"));
    const t1 = await cached("/chat/sessions", 30_000, true, () => backend("t1"));
    const t2 = await cached("/chat/sessions", 30_000, true, () => backend("t2"));

    expect(backendCalls).toBe(3);
    expect(t1).toBe("t1");
    expect(t2).toBe("t2"); // 不被 30s 缓存挡住（否则 AI 标题永远等不到）
  });

  it("不同资源互不干扰（失效 /chat 不影响 /models）", async () => {
    await cached("/chat/sessions", 30_000, false, () => backend("chat"));
    await cached("/models/providers", 30_000, false, () => backend("models"));
    expect(backendCalls).toBe(2);

    invalidateForPath("/chat/sessions");
    await cached("/chat/sessions", 30_000, false, () => backend("chat2"));
    await cached("/models/providers", 30_000, false, () => backend("models"));

    expect(backendCalls).toBe(3); // 只有 chat 重取
  });
});
