import { describe, it, expect, beforeEach, vi } from "vitest";
import { cached, invalidate, invalidateForPath, invalidateAll, cacheStats, resetStats } from "./requestCache";

/**
 * 这层缓存的价值全在「什么该命中、什么绝不能命中」上，所以这里是有断言的
 * 单测（与项目「单测仅冒烟」的约定不同——协议的边界条件必须钉住，否则一个
 * 写后读脏的 bug 会非常难查）。项目其余单测仍保持无断言风格。
 */
describe("requestCache", () => {
  beforeEach(() => {
    invalidateAll();
    resetStats();
    vi.useRealTimers();
  });

  it("TTL 内命中，不重复执行 fetcher", async () => {
    let calls = 0;
    const fetch1 = () => Promise.resolve(++calls);

    const a = await cached("k", 1000, false, fetch1);
    const b = await cached("k", 1000, false, fetch1);

    expect(a).toBe(1);
    expect(b).toBe(1);
    expect(calls).toBe(1);
  });

  it("TTL 过期后重新取", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);
    vi.useFakeTimers();

    await cached("k", 1000, false, fetcher);
    vi.advanceTimersByTime(1001);
    const second = await cached("k", 1000, false, fetcher);

    expect(second).toBe(2);
    expect(calls).toBe(2);
  });

  it("force 跳过缓存", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("k", 1000, false, fetcher);
    const forced = await cached("k", 1000, true, fetcher);

    expect(forced).toBe(2);
  });

  it("并发同 key 只发一次请求（其余复用同一个 Promise）", async () => {
    let calls = 0;
    let release: (v: number) => void = () => {};
    const fetcher = () => {
      calls++;
      return new Promise<number>((resolve) => {
        release = resolve;
      });
    };

    const p1 = cached("k", 1000, false, fetcher);
    const p2 = cached("k", 1000, false, fetcher);
    const p3 = cached("k", 1000, false, fetcher);
    expect(calls).toBe(1);

    release(42);
    expect(await p1).toBe(42);
    expect(await p2).toBe(42);
    expect(await p3).toBe(42);
  });

  it("失效后同 key 会重新取（写后必须能读到新值）", async () => {
    let value = 1;
    const fetcher = () => Promise.resolve(value);

    expect(await cached("k", 10_000, false, fetcher)).toBe(1);
    value = 2;
    invalidate("k");
    expect(await cached("k", 10_000, false, fetcher)).toBe(2);
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
    const fresh = await cached("k", 10_000, false, () => Promise.resolve(`fresh${++calls}`));
    expect(fresh).toBe("fresh1");
    expect(calls).toBe(1);
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
    expect(chatCalls).toBe(4);
    // 相邻资源不受影响（/models 不该被 /chat 的失效波及）
    await cached("/models/providers", 10_000, false, other);
    expect(otherCalls).toBe(1);
  });

  it("前缀边界正确：/chat 不外溢到 /chatx", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("/chatx/thing", 10_000, false, fetcher);
    invalidate("/chat");
    await cached("/chatx/thing", 10_000, false, fetcher);

    expect(calls).toBe(1); // 未失效，仍是缓存值
  });

  it("ttl<=0 只去重不留存", async () => {
    let calls = 0;
    const fetcher = () => Promise.resolve(++calls);

    await cached("k", 0, false, fetcher);
    await cached("k", 0, false, fetcher);

    expect(calls).toBe(2);
  });

  it("统计能反映命中与去重（用于验证这层真的在工作）", async () => {
    const fetcher = () => Promise.resolve(1);
    await cached("k", 10_000, false, fetcher);
    await cached("k", 10_000, false, fetcher);

    const s = cacheStats();
    expect(s.hits).toBe(1);
    expect(s.misses).toBe(1);
    expect(s.size).toBe(1);
  });
});
