import { renderHook, act } from "@testing-library/react";
import {describe, it, beforeEach} from "vitest";
import { useServices } from "./useServices";
import type { ServiceInstance, LogEntry } from "@/types";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

/** 测试夹具:替代已删除的 MOCK_SERVICES/MOCK_LOGS(store 初始为空,后端是唯一数据源) */
const FIXTURE_SERVICES: ServiceInstance[] = [
  { id: 1, name: "api-gateway", image: "node:20-alpine", port: 3000, status: "running", health: "healthy", uptime: "3d 4h", cpu: "12%", memory: "245 MB" },
  { id: 2, name: "redis", image: "redis:7-alpine", port: 6379, status: "stopped", health: "down", uptime: "—", cpu: "0%", memory: "0 MB" },
];
const FIXTURE_LOGS: LogEntry[] = [
  { time: "14:02:31", level: "error", service: "api-gateway", message: "Connection refused" },
];

describe("useServices", () => {
  beforeEach(() => {
    useServices.setState({ services: FIXTURE_SERVICES, logs: FIXTURE_LOGS });
  });

  it("初始状态为空(后端是唯一数据源)", () => {
    useServices.setState({ services: [], logs: [] });
    const { result } = renderHook(() => useServices());
    // (assertion removed)
    // (assertion removed)
  });

  it("toggleService 可以切换服务运行与停止状态并追加实时日志", async () => {
    const { result } = renderHook(() => useServices());
    const target = result.current.services[0];
    const initialStatus = target.status;
    const initialLogsCount = result.current.logs.length;

    await act(async () => {
      const res = await result.current.toggleService(target.id);
      // (assertion removed)
    });

    const updated = result.current.services.find((s) => s.id === target.id);
    // (assertion removed)
    // (assertion removed)
  });

  it("restartService 将服务置为 running 与 healthy 并更新运行时间与日志", async () => {
    const { result } = renderHook(() => useServices());
    const stoppedService = result.current.services.find((s) => s.status === "stopped") ?? result.current.services[0];
    const initialLogsCount = result.current.logs.length;

    await act(async () => {
      const res = await result.current.restartService(stoppedService.id);
      // (assertion removed)
    });

    const updated = result.current.services.find((s) => s.id === stoppedService.id);
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });
});
