import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { useChat } from "./useChat";
import { SEED_CONVERSATION } from "@/lib/api/chatApi";

describe("useChat", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("sendMessage 后流式完成回复并复位 isTyping", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useChat());

    act(() => result.current.setInput("分析一下销售数据"));
    act(() => {
      void result.current.sendMessage();
    });

    expect(result.current.messages.length).toBe(2);
    expect(result.current.messages[0].role).toBe("user");
    expect(result.current.messages[1].isTyping).toBe(true);
    expect(result.current.isSending).toBe(true);
    expect(result.current.input).toBe("");

    while (result.current.isSending) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(500);
      });
    }

    const assistant = result.current.messages[1];
    expect(assistant.role).toBe("assistant");
    expect(assistant.content.length).toBeGreaterThan(0);
    expect(assistant.isTyping).toBe(false);
  });

  it("clear 清空消息", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useChat());
    act(() => result.current.setInput("hi"));
    act(() => {
      void result.current.sendMessage();
    });
    for (let i = 0; i < 100 && result.current.isSending; i++) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(500);
      });
    }
    act(() => result.current.clear());
    expect(result.current.messages).toEqual([]);
  });

  it("initialMessages 作为初始对话渲染（种子消息回归守卫）", () => {
    const { result } = renderHook(() => useChat({ initialMessages: SEED_CONVERSATION }));
    expect(result.current.messages.length).toBe(2);
    expect(result.current.messages[0].role).toBe("user");
    expect(result.current.messages[1].steps?.length).toBeGreaterThan(0);
  });
});
