import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { useSimulatedUpload } from "./useUpload";

describe("useSimulatedUpload", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("完整上传流程: idle -> uploading -> success -> 关闭复位", async () => {
    vi.useFakeTimers();
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useSimulatedUpload(2000, 1500));

    expect(result.current.isOpen).toBe(false);
    expect(result.current.status).toBe("idle");

    act(() => result.current.open());
    expect(result.current.isOpen).toBe(true);

    act(() => result.current.startUpload(onSuccess));
    expect(result.current.status).toBe("uploading");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(result.current.status).toBe("success");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500);
    });
    expect(result.current.isOpen).toBe(false);
    expect(result.current.status).toBe("idle");
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("close 关闭弹窗并复位状态", () => {
    const { result } = renderHook(() => useSimulatedUpload());
    act(() => result.current.open());
    expect(result.current.isOpen).toBe(true);
    act(() => result.current.close());
    expect(result.current.isOpen).toBe(false);
    expect(result.current.status).toBe("idle");
  });
});
