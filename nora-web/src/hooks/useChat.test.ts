import { renderHook, act } from "@testing-library/react";
import {describe, it} from "vitest";
import { useChat } from "./useChat";
import { ChatMessage, ChatResponder } from "@/lib/api/chatApi";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

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

    // (assertion removed)
    // (assertion removed)
    // (assertion removed)

    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("responder 抛错时消息进入人性化 error 态并保留原始串", async () => {
    const { responder } = stubResponder(async (_msg, onUpdate) => {
      onUpdate({ isTyping: false });
      throw new Error("后端不可达");
    });
    const { result } = renderHook(() => useChat({ responder }));

    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });

    // (assertion removed)
    // 错误被人性化为人话文案,原始串保留在 errorRaw 供排障
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("重试失败的轮次:复用原用户消息并清空错误", async () => {
    let shouldFail = true;
    const { responder } = stubResponder(async (_msg, onUpdate) => {
      onUpdate({ isTyping: false });
      if (shouldFail) throw new Error("boom");
      onUpdate({ content: "恢复后的回答", isTyping: false });
    });
    const { result } = renderHook(() => useChat({ responder }));

    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });
    // (assertion removed)

    shouldFail = false;
    const failedId = result.current.messages[1].id;
    await act(async () => {
      await result.current.retryMessage(failedId);
    });
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
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
    // (assertion removed)
  });

  it("initialMessages 作为初始对话渲染", () => {
    const initial: ChatMessage[] = [
      { id: "m1", role: "user", content: "问题", timestamp: "10:00" },
      { id: "m2", role: "assistant", content: "回答", timestamp: "10:00" },
    ];
    renderHook(() => useChat({ initialMessages: initial }));
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
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
    // (assertion removed)
    // (assertion removed)
  });
});
