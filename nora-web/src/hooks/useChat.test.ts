import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { useChat } from "./useChat";
import { ChatMessage, ChatResponder } from "@/lib/api/chatApi";

/** 可控的桩 responder:代替真实 AgentAPI,模拟流式回包 */
function stubResponder(
  onMessage?: (msg: string, onUpdate: (p: Partial<ChatMessage>) => void) => Promise<void>
): { responder: ChatResponder; updates: Partial<ChatMessage>[] } {
  const updates: Partial<ChatMessage>[] = [];
  const responder: ChatResponder = async (message, onUpdate) => {
    updates.push({ content: "" });
    if (onMessage) await onMessage(message, onUpdate);
  };
  return { responder, updates };
}

describe("useChat", () => {
  it("sendMessage 后流式完成回复并复位 isTyping", async () => {
    const { responder } = stubResponder(async (_msg, onUpdate) => {
      onUpdate({ content: "你" });
      onUpdate({ content: "你好" });
      onUpdate({ isTyping: false });
    });
    const { result } = renderHook(() => useChat({ responder }));

    act(() => result.current.setInput("分析一下销售数据"));
    await act(async () => {
      await result.current.sendMessage();
    });

    expect(result.current.isSending).toBe(false);
    expect(result.current.messages.length).toBe(2);
    expect(result.current.messages[0].role).toBe("user");
    const assistant = result.current.messages[1];
    expect(assistant.role).toBe("assistant");
    expect(assistant.content).toBe("你好");
    expect(assistant.isTyping).toBe(false);
  });

  it("responder 抛错时消息进入 error 态并结束发送", async () => {
    const { responder } = stubResponder(async (_msg, onUpdate) => {
      onUpdate({ isTyping: false });
      throw new Error("后端不可达");
    });
    const { result } = renderHook(() => useChat({ responder }));

    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });

    expect(result.current.isSending).toBe(false);
    expect(result.current.messages[1].error).toBe("后端不可达");
  });

  it("clear 清空消息", async () => {
    const { responder } = stubResponder(async (_m, onUpdate) => {
      onUpdate({ content: "ok", isTyping: false });
    });
    const { result } = renderHook(() => useChat({ responder }));
    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });
    act(() => result.current.clear());
    expect(result.current.messages).toEqual([]);
  });

  it("initialMessages 作为初始对话渲染", () => {
    const initial: ChatMessage[] = [
      { id: "m1", role: "user", content: "问题", timestamp: "10:00" },
      { id: "m2", role: "assistant", content: "回答", timestamp: "10:00" },
    ];
    const { result } = renderHook(() => useChat({ initialMessages: initial }));
    expect(result.current.messages.length).toBe(2);
    expect(result.current.messages[0].role).toBe("user");
    expect(result.current.messages[1].content).toBe("回答");
  });

  it("空输入不触发发送", async () => {
    const calls: string[] = [];
    const { responder } = stubResponder(async (msg) => {
      calls.push(msg);
    });
    const { result } = renderHook(() => useChat({ responder }));
    act(() => result.current.setInput("   "));
    await act(async () => {
      await result.current.sendMessage();
    });
    expect(calls).toEqual([]);
    expect(result.current.messages.length).toBe(0);
  });
});
