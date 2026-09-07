import { describe, it, expect } from "vitest";
import { relativeTime } from "./relativeTime";

const NOW = new Date("2026-09-08T12:00:00").getTime();
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe("relativeTime", () => {
  it("刚刚(<1分钟)", () => {
    expect(relativeTime(NOW - 30_000, NOW)).toBe("刚刚");
    expect(relativeTime(NOW, NOW)).toBe("刚刚");
  });

  it("N分钟前", () => {
    expect(relativeTime(NOW - 5 * MIN, NOW)).toBe("5分钟前");
    expect(relativeTime(NOW - 59 * MIN, NOW)).toBe("59分钟前");
  });

  it("N小时前", () => {
    expect(relativeTime(NOW - 3 * HOUR, NOW)).toBe("3小时前");
    expect(relativeTime(NOW - 23 * HOUR, NOW)).toBe("23小时前");
  });

  it("小时级优先于昨天标签(信息更精确);超过24h显示昨天", () => {
    // 昨天 23:00 → 今天 12:00 = 13h,小时精度更精确
    const yesterdayLate = new Date("2026-09-07T23:00:00").getTime();
    const todayNoon = new Date("2026-09-08T12:00:00").getTime();
    expect(relativeTime(yesterdayLate, todayNoon)).toBe("13小时前");
    // 昨天 10:00 → 今天 11:00 = 25h,超过一天,显示「昨天」
    const yesterdayAm = new Date("2026-09-07T10:00:00").getTime();
    const todayAm = new Date("2026-09-08T11:00:00").getTime();
    expect(relativeTime(yesterdayAm, todayAm)).toBe("昨天");
  });

  it("今年其他日期", () => {
    expect(relativeTime(NOW - 5 * DAY, NOW)).toBe("9月3日");
  });

  it("往年带年份", () => {
    expect(relativeTime(new Date("2025-03-01T10:00:00").getTime(), NOW)).toBe("2025年3月1日");
  });

  it("非法时间戳返回空串", () => {
    expect(relativeTime(NaN, NOW)).toBe("");
  });
});
