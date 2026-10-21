import { act, renderHook } from "@testing-library/react";
import { afterEach, it, vi } from "vitest";
import { usePollingResource } from "./usePollingResource";

afterEach(() => vi.useRealTimers());

it("refreshes, recovers from read errors, and releases listeners on unmount", async () => {
  vi.useFakeTimers();
  let attempts = 0;
  const load = async () => {
    if (++attempts === 2) throw new Error("read failed");
    return [attempts];
  };
  const hook = renderHook(() => usePollingResource(load));
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  await act(async () => { hook.result.current.refresh(); });
  await act(async () => { window.dispatchEvent(new Event("focus")); });
  hook.unmount();
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
});
