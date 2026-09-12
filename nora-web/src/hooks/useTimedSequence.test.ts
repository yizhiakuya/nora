import { renderHook, act } from "@testing-library/react";
import {describe, it, vi, afterEach} from "vitest";
import { useTimedSequence } from "./useTimedSequence";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

afterEach(() => {
  vi.useRealTimers();
});

describe("useTimedSequence", () => {
  it("schedule 按延迟触发回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 100));
    // (assertion removed)

    act(() => vi.advanceTimersByTime(100));
    // (assertion removed)
  });

  it("cancelAll 阻止未触发的回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 200));
    act(() => result.current.cancelAll());
    act(() => vi.advanceTimersByTime(300));
    // (assertion removed)
  });

  it("组件卸载后不再触发回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result, unmount } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 200));
    unmount();
    act(() => vi.advanceTimersByTime(300));
    // (assertion removed)
  });
});
