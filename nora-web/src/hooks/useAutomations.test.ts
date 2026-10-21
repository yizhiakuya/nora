import { afterEach, beforeEach, describe, it, vi } from "vitest";
import { useAutomations } from "./useAutomations";
import { useNotifications } from "./useNotifications";
import { automationsApi } from "@/lib/services/automationsApi";
import type { AutomationRule, ExecutionRecord } from "@/types";

const rule: AutomationRule = { id: 1, name: "任务", trigger: "手动触发", action: "只回复确认", enabled: true, status: "active", lastRun: "—" };
const execution: ExecutionRecord = { id: 1, ruleId: 1, ruleName: rule.name, status: "success", detail: "确认", duration: "1s", time: "10:00" };

beforeEach(() => {
  useAutomations.setState({ rules: [rule], executions: [execution], runningRuleIds: [], togglingRuleIds: [], syncError: null });
  vi.spyOn(automationsApi, "listRules").mockResolvedValue([rule]);
  vi.spyOn(automationsApi, "listExecutions").mockResolvedValue([execution]);
  vi.spyOn(automationsApi, "createRule").mockResolvedValue(rule);
  vi.spyOn(automationsApi, "toggleRule").mockResolvedValue({ ...rule, enabled: false, status: "paused" });
  vi.spyOn(automationsApi, "runRule").mockResolvedValue(execution);
  vi.spyOn(useNotifications.getState(), "addNotification").mockResolvedValue();
  vi.spyOn(useNotifications.getState(), "syncFromBackend").mockResolvedValue();
});
afterEach(() => vi.restoreAllMocks());

describe("自动任务状态冒烟", () => {
  it("服务端创建、启停、运行和重试共用入口", async () => {
    const state = useAutomations.getState();
    await state.syncFromBackend();
    await state.addRule(rule.name, "manual", { actionType: "agent", prompt: "确认" });
    await Promise.all([state.toggleRule(1), state.toggleRule(1)]);
    await Promise.all([state.markRun(1), state.retryExecution(1)]);
    await state.syncFromBackend();
  });

  it("失败分支释放等待状态并保留服务端快照", async () => {
    const failure = new Error("服务不可用");
    vi.mocked(automationsApi.listRules).mockRejectedValue(failure);
    vi.mocked(automationsApi.createRule).mockRejectedValue(failure);
    vi.mocked(automationsApi.toggleRule).mockRejectedValue(failure);
    vi.mocked(automationsApi.runRule).mockRejectedValue(failure);
    const state = useAutomations.getState();
    await state.syncFromBackend();
    await state.addRule(rule.name, "manual", { actionType: "agent", prompt: "确认" });
    await state.toggleRule(1);
    await state.markRun(1);
    await state.retryExecution(-1);
    await state.syncFromBackend();
  });

  it("慢同步与成功写入交错", async () => {
    let release!: (rules: AutomationRule[]) => void;
    vi.mocked(automationsApi.listRules).mockReturnValueOnce(new Promise(resolve => { release = resolve; }));
    const state = useAutomations.getState();
    const sync = state.syncFromBackend();
    await state.toggleRule(1);
    release([rule]);
    await sync;
  });
});
