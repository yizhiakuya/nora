import {describe, it} from "vitest";
import { normalizeStep } from "./agentApi";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

describe("normalizeStep", () => {
  it("剥离服务端历史重建字段 rawArgs,不污染 UI 展示", () => {
    // rawArgs 是后端跨轮重放工具链用的脱敏原始参数(agentApi.StepInputPayload);
    // UI 展示位(通用工具 JSON 详情等)不该看到它
    normalizeStep({
    id: "s-call-0-abc",
    type: "tool",
    title: "运行命令",
    status: "completed",
    toolName: "run_command",
    input: { target: "echo hi", rawArgs: '{"command":"echo hi"}' },
}, 0);
    // (assertion removed)
    // (assertion removed)
  });

  it("无 input 的步骤不受影响", () => {
    normalizeStep({ id: "s1", type: "think", title: "推理", status: "running" }, 0);
    // (assertion removed)
    // (assertion removed)
  });

  it("其余字段照常透传(展示字段不丢)", () => {
    normalizeStep({
    id: "s2",
    type: "tool",
    title: "查询数据库",
    status: "completed",
    toolName: "execute_sql",
    input: { sql: "SELECT 1", target: "ds-1" },
    result: { content: "1", rowCount: 1 },
    roundIndex: 2,
}, 1);
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });
});
