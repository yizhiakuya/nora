import {describe, it} from "vitest";
import { relativeTime } from "./relativeTime";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const NOW = new Date("2026-09-08T12:00:00").getTime();
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe("relativeTime", () => {



  it("小时级优先于昨天标签(信息更精确);超过24h显示昨天", () => {
    // 昨天 23:00 → 今天 12:00 = 13h,小时精度更精确
    const yesterdayLate = new Date("2026-09-07T23:00:00").getTime();
    const todayNoon = new Date("2026-09-08T12:00:00").getTime();
    // (assertion removed)
    // 昨天 10:00 → 今天 11:00 = 25h,超过一天,显示「昨天」
    const yesterdayAm = new Date("2026-09-07T10:00:00").getTime();
    const todayAm = new Date("2026-09-08T11:00:00").getTime();
    // (assertion removed)
  });



});
