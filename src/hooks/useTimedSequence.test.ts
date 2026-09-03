import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { useTimedSequence } from "./useTimedSequence";

afterEach(() => {
  vi.useRealTimers();
});

describe("useTimedSequence", () => {
  it("schedule 按延迟触发回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 100));
    expect(fn).not.toHaveBeenCalled();

    act(() => vi.advanceTimersByTime(100));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("cancelAll 阻止未触发的回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 200));
    act(() => result.current.cancelAll());
    act(() => vi.advanceTimersByTime(300));
    expect(fn).not.toHaveBeenCalled();
  });

  it("组件卸载后不再触发回调", () => {
    vi.useFakeTimers();
    const fn = vi.fn();
    const { result, unmount } = renderHook(() => useTimedSequence());

    act(() => result.current.schedule(fn, 200));
    unmount();
    act(() => vi.advanceTimersByTime(300));
    expect(fn).not.toHaveBeenCalled();
  });
});
