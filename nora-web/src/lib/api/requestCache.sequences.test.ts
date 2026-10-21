import { describe, it, beforeEach } from "vitest";
import { cached, invalidateForPath, invalidateAll } from "./requestCache";

// 模拟调用顺序的冒烟；真实 HTTP 行为在 src/e2e/standards.html 验收。
describe("requestCache 调用序列冒烟", () => {
  const load = async (value: string) => value;
  beforeEach(invalidateAll);

  it("并发列表、历史回访、写后失效与强制刷新", async () => {
    await Promise.all(Array.from({ length: 6 }, () => cached("/chat/sessions", 30000, false, () => load("list"))));
    for (const id of ["a", "b", "c", "a", "b"]) {
      await cached(`/chat/sessions/${id}/messages`, 30000, false, () => load(id));
    }
    invalidateForPath("/chat/sessions");
    await cached("/chat/sessions", 30000, false, () => load("updated"));
    await cached("/chat/sessions", 30000, true, () => load("forced"));
    await cached("/models/providers", 30000, false, () => load("models"));
    invalidateForPath("/chat/sessions");
    await cached("/models/providers", 30000, false, () => load("models"));
  });
});
