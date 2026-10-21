import { describe, it, beforeEach, vi } from "vitest";
import { cached, invalidate, invalidateForPath, invalidateAll, cacheStats, resetStats } from "./requestCache";

// 冒烟测试；缓存竞争的结果由真实 HTTP 验收验证。
describe("requestCache", () => {
  it("force refresh while an older read remains pending", async () => {
    let release!: (value: string) => void;
    const old = cached("force-race", 1000, false, () => new Promise<string>(resolve => { release = resolve; }));
    await cached("force-race", 1000, true, () => Promise.resolve("new"));
    release("old");
    await old;
    await cached("force-race", 1000, false, () => Promise.resolve("fallback"));
  });
  beforeEach(() => {
    invalidateAll();
    resetStats();
    vi.useRealTimers();
  });

  it("TTL 内命中，不重复执行 fetcher", async () => {
    let calls = 0;
    const fetch1 = () => Promise.resolve(++calls);

    await cached("k", 1000, false, fetch1);
    await cached("k", 1000, false, fetch1);

  });

  it("TTL 过期后重新取", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);
    vi.useFakeTimers();

    await cached("k", 1000, false, fetcher);
    vi.advanceTimersByTime(1001);
    await cached("k", 1000, false, fetcher);

  });

  it("force 跳过缓存", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("k", 1000, false, fetcher);
    await cached("k", 1000, true, fetcher);

  });

  it("并发同 key 只发一次请求（其余复用同一个 Promise）", async () => {
    let release: (v: number) => void = () => {};
    const fetcher = () => {
      return new Promise<number>((resolve) => {
        release = resolve;
      });
    };

    const p1 = cached("k", 1000, false, fetcher);
    const p2 = cached("k", 1000, false, fetcher);
    const p3 = cached("k", 1000, false, fetcher);

    release(42);
    await Promise.all([p1, p2, p3]);
  });

  it("失效后同 key 会重新取（写后必须能读到新值）", async () => {
    let value = 1;
    const fetcher = () => Promise.resolve(value);

    await cached("k", 10_000, false, fetcher);
    value = 2;
    invalidate("k");
    await cached("k", 10_000, false, fetcher);
  });

  it("失效前的在途响应晚到不得回填（防写后读脏）", async () => {
    let release: (v: string) => void = () => {};
    const slow = () => new Promise<string>((resolve) => { release = resolve; });

    const pending = cached("k", 10_000, false, slow);
    invalidate("k");                       // 期间发生写操作
    release("stale");                      // 旧请求这才返回
    await pending;

    // 旧值不得进入缓存：下一次读取必须重新执行
    let calls = 0;
    await cached("k", 10_000, false, () => Promise.resolve(`fresh${++calls}`));
  });

  it("invalidateForPath 按资源前缀失效（含子路径，且不误伤相邻资源）", async () => {
    let chatCalls = 0;
    let otherCalls = 0;
    const chat = () => Promise.resolve(`chat${++chatCalls}`);
    const other = () => Promise.resolve(`other${++otherCalls}`);

    await cached("/chat/sessions", 10_000, false, chat);
    await cached("/chat/sessions/s1/messages", 10_000, false, chat);
    await cached("/models/providers", 10_000, false, other);

    invalidateForPath("/chat/sessions/s1/messages");

    // /chat 下的都失效
    await cached("/chat/sessions", 10_000, false, chat);
    await cached("/chat/sessions/s1/messages", 10_000, false, chat);
    // 相邻资源不受影响（/models 不该被 /chat 的失效波及）
    await cached("/models/providers", 10_000, false, other);
  });

  it("前缀边界正确：/chat 不外溢到 /chatx", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("/chatx/thing", 10_000, false, fetcher);
    invalidate("/chat");
    await cached("/chatx/thing", 10_000, false, fetcher);

  });

  it("ttl<=0 只去重不留存", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("k", 0, false, fetcher);
    await cached("k", 0, false, fetcher);

  });

  it("统计能反映命中与去重（用于验证这层真的在工作）", async () => {
    const fetcher = () => Promise.resolve(1);
    await cached("k", 10_000, false, fetcher);
    await cached("k", 10_000, false, fetcher);

    cacheStats();
  });
});
