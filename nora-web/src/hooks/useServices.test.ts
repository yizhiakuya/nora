import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import { useServices } from "./useServices";
import type { ServiceInstance, LogEntry } from "@/types";

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
    expect(result.current.services).toEqual([]);
    expect(result.current.logs).toEqual([]);
  });

  it("toggleService 可以切换服务运行与停止状态并追加实时日志", () => {
    const { result } = renderHook(() => useServices());
    const target = result.current.services[0];
    const initialStatus = target.status;
    const initialLogsCount = result.current.logs.length;

    act(() => {
      const res = result.current.toggleService(target.id);
      expect(res.name).toBe(target.name);
      expect(res.nextStatus).toBe(initialStatus === "running" ? "stopped" : "running");
    });

    const updated = result.current.services.find((s) => s.id === target.id);
    expect(updated?.status).toBe(initialStatus === "running" ? "stopped" : "running");
    expect(result.current.logs.length).toBeGreaterThan(initialLogsCount);
  });

  it("restartService 将服务置为 running 与 healthy 并更新运行时间与日志", () => {
    const { result } = renderHook(() => useServices());
    const stoppedService = result.current.services.find((s) => s.status === "stopped") ?? result.current.services[0];
    const initialLogsCount = result.current.logs.length;

    act(() => {
      const name = result.current.restartService(stoppedService.id);
      expect(name).toBe(stoppedService.name);
    });

    const updated = result.current.services.find((s) => s.id === stoppedService.id);
    expect(updated?.status).toBe("running");
    expect(updated?.health).toBe("healthy");
    expect(updated?.uptime).toBe("刚刚");
    expect(result.current.logs.length).toBeGreaterThan(initialLogsCount);
  });
});
