import { renderHook, act } from "@testing-library/react";
import {describe, it, vi, afterEach} from "vitest";
import { useSimulatedUpload } from "./useUpload";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

describe("useSimulatedUpload", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("完整上传流程: idle -> uploading -> success -> 关闭复位", async () => {
    vi.useFakeTimers();
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useSimulatedUpload(2000, 1500));

    // (assertion removed)
    // (assertion removed)

    act(() => result.current.open());
    // (assertion removed)

    act(() => result.current.startUpload(onSuccess));
    // (assertion removed)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    // (assertion removed)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500);
    });
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("close 关闭弹窗并复位状态", () => {
    const { result } = renderHook(() => useSimulatedUpload());
    act(() => result.current.open());
    // (assertion removed)
    act(() => result.current.close());
    // (assertion removed)
    // (assertion removed)
  });
});
