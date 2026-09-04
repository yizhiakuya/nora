import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import { useServices } from "./useServices";
import { MOCK_SERVICES, MOCK_LOGS } from "@/lib/devData";

describe("useServices", () => {
  beforeEach(() => {
    useServices.setState({ services: MOCK_SERVICES, logs: MOCK_LOGS });
  });

  it("初始化包含预置服务与日志列表", () => {
    const { result } = renderHook(() => useServices());
    expect(result.current.services.length).toBe(MOCK_SERVICES.length);
    expect(result.current.logs.length).toBe(MOCK_LOGS.length);
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
