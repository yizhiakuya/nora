import { describe, it } from "vitest";
import { relativeTime } from "./relativeTime";

describe("relativeTime", () => {
  it("执行无效值、未来时间和各时间粒度路径", () => {
    const now = new Date("2026-09-08T12:00:00").getTime();
    for (const ts of [NaN, now + 60_000, now, now - 120_000, now - 3_600_000,
      new Date("2026-09-07T23:00:00").getTime(),
      new Date("2026-09-07T10:00:00").getTime(),
      new Date("2026-08-01T12:00:00").getTime(),
      new Date("2025-09-08T12:00:00").getTime()]) {
      relativeTime(ts, now);
    }
    relativeTime(Date.now());
  });
});
