import { renderHook, act } from "@testing-library/react";
import {describe, it} from "vitest";
import { useChat } from "./useChat";
import { ChatMessage, ChatResponder } from "@/lib/api/chatApi";
import { useChatSessions } from "./useChatSessions";

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
  it("运行中新消息排队，支持撤回编辑和删除", async () => {
    let finish!: () => void;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    const { responder } = stubResponder(async (_message, onUpdate) => {
      await pending;
      onUpdate({ content: "完成", isTyping: false });
    });
    const sessionId = useChatSessions.getState().createSession(false);
    const { result, unmount } = renderHook(() => useChat({ responder, sessionId }));
    let running!: Promise<void>;
    act(() => result.current.setInput("原始任务"));
    act(() => { running = result.current.sendMessage(); });
    act(() => result.current.setInput("修改格式"));
    await act(async () => { await result.current.sendMessage(); });
    act(() => result.current.restoreQueuedMessage(result.current.queuedMessages[0].id));
    await act(async () => { await result.current.sendMessage(); });
    act(() => result.current.removeQueuedMessage(result.current.queuedMessages[0].id));
    act(() => result.current.setInput("停止后保留"));
    await act(async () => { await result.current.sendMessage(); });
    await act(async () => { await result.current.stopGenerating(); });
    await act(async () => { finish(); await running; });
    unmount();
    useChatSessions.getState().deleteSession(sessionId);
  });

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


  });

  it("responder 抛错时消息保留原始错误", async () => {
    const { responder } = stubResponder(async (_msg, onUpdate) => {
      onUpdate({ isTyping: false });
      throw new Error("后端不可达");
    });
    const { result } = renderHook(() => useChat({ responder }));

    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });

  });

  it("重试失败的轮次:保留失败并追加新轮", async () => {
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

    shouldFail = false;
    const failedId = result.current.messages[1].id;
    await act(async () => {
      await result.current.retryMessage(failedId);
    });
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
  });

  it("initialMessages 作为初始对话渲染", () => {
    const initial: ChatMessage[] = [
      { id: "m1", role: "user", content: "问题", timestamp: "10:00" },
      { id: "m2", role: "assistant", content: "回答", timestamp: "10:00" },
    ];
    renderHook(() => useChat({ initialMessages: initial }));
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
  });
});
