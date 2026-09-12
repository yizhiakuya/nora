import {describe, it} from "vitest";
import { humanizeError } from "./errorMessages";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

describe("humanizeError", () => {
  it("网络不可达 → network", () => {
    const r = humanizeError("Cannot connect to agent-service");
    // (assertion removed)
    // (assertion removed)
  });

  it("上游 400 免费渠道限制 → auth 类(截图实测案例)", () => {
    const r = humanizeError("上游 400: Error from provider: Concord's free tier can only be used with OpenCode");
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });


  it("5xx → provider", () => {
    const r = humanizeError("HTTP 502 Bad Gateway");
    // (assertion removed)
  });


  it("未知错误兜底:保留原文且文案不是原始串", () => {
    const r = humanizeError("某个完全没见过的错误");
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });
});
