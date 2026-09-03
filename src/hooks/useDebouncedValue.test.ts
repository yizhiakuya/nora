import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { useDebouncedValue } from "./useDebouncedValue";

afterEach(() => {
  vi.useRealTimers();
});

describe("useDebouncedValue", () => {
  it("延迟内不更新，延迟后更新", () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ value, delay }: { value: string; delay: number }) => useDebouncedValue(value, delay),
      { initialProps: { value: "a", delay: 100 } }
    );

    expect(result.current).toBe("a");

    rerender({ value: "b", delay: 100 });
    act(() => vi.advanceTimersByTime(50));
    expect(result.current).toBe("a"); // 还在延迟期内

    act(() => vi.advanceTimersByTime(50));
    expect(result.current).toBe("b");
  });

  it("快速连续变化只保留最终值", () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ value }: { value: number }) => useDebouncedValue(value, 200),
      { initialProps: { value: 0 } }
    );

    rerender({ value: 1 });
    rerender({ value: 2 });
    rerender({ value: 3 });
    act(() => vi.advanceTimersByTime(200));
    expect(result.current).toBe(3);
  });
});
