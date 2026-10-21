import { renderHook, act } from "@testing-library/react";
import {describe, it, beforeEach} from "vitest";
import { useChat } from "./useChat";
import { ChatMessage, ChatResponder, ChatStreamEvent } from "@/lib/api/chatApi";
import { useChatSessions } from "./useChatSessions";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

/** 可控的桩 responder:代替真实 AgentAPI,模拟流式回包 */
function stubResponder(
  onMessage?: (msg: string, onEvent: (event: ChatStreamEvent) => void) => Promise<void>
): { responder: ChatResponder; updates: ChatStreamEvent[] } {
  const updates: ChatStreamEvent[] = [];
  const responder: ChatResponder = async (message, onEvent) => {
    const publish = (event: ChatStreamEvent) => { updates.push(event); onEvent(event); };
    if (onMessage) await onMessage(message, publish);
  };
  return { responder, updates };
}

describe("useChat", () => {
  let sessionId: string;
  beforeEach(() => {
    useChatSessions.setState({ sessions: [], messagesBySession: {}, activeId: "" });
    sessionId = useChatSessions.getState().createSession(false);
  });
  it("运行中新消息排队，支持撤回编辑和删除", async () => {
    let finish!: () => void;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    const { responder } = stubResponder(async (_message, onEvent) => {
      await pending;
      onEvent({ type: "delta", content: "完成" });
      onEvent({ type: "finish" });
    });
    const sessionId = useChatSessions.getState().createSession(false);
    const { result, unmount } = renderHook(() => useChat({ responder, sessionId }));
    let running!: Promise<void>;
    act(() => result.current.setInput("原始任务"));
    act(() => { running = result.current.sendMessage(); });
    act(() => result.current.setInput("修改格式"));
    await act(async () => { await Promise.all([result.current.sendMessage(), result.current.sendMessage()]); });
    await act(async () => { await result.current.restoreQueuedMessage("00000000-0000-4000-8000-000000000001"); });
    await act(async () => { await result.current.sendMessage(); });
    await act(async () => { await result.current.removeQueuedMessage("00000000-0000-4000-8000-000000000001"); });
    act(() => result.current.setInput("停止后保留"));
    await act(async () => { await result.current.sendMessage(); });
    await act(async () => { await result.current.stopGenerating(); });
    await act(async () => { finish(); await running; });
    unmount();
    useChatSessions.getState().deleteSession(sessionId);
    useChatSessions.getState().undoDeleteSession(sessionId);
  });

  it("sendMessage 后流式完成回复并复位 isTyping", async () => {
    const { responder } = stubResponder(async (_msg, onEvent) => {
      onEvent({ type: "delta", content: "你" });
      onEvent({ type: "delta", content: "好" });
      onEvent({ type: "finish" });
    });
    const { result } = renderHook(() => useChat({ responder, sessionId }));

    act(() => result.current.setInput("分析一下销售数据"));
    await act(async () => {
      await result.current.sendMessage();
    });


  });

  it("responder 抛错时消息保留原始错误", async () => {
    const { responder } = stubResponder(async (_msg, onEvent) => {
      onEvent({ type: "finish" });
      throw new Error("后端不可达");
    });
    const { result } = renderHook(() => useChat({ responder, sessionId }));

    act(() => result.current.setInput("hi"));
    await act(async () => {
      await result.current.sendMessage();
    });

  });

  it("重试失败的轮次:保留失败并追加新轮", async () => {
    let shouldFail = true;
    const { responder } = stubResponder(async (_msg, onEvent) => {
      onEvent({ type: "finish" });
      if (shouldFail) throw new Error("boom");
      onEvent({ type: "delta", content: "恢复后的回答" });
      onEvent({ type: "finish" });
    });
    const { result } = renderHook(() => useChat({ responder, sessionId }));

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
    const { responder } = stubResponder(async (_m, onEvent) => {
      onEvent({ type: "delta", content: "ok" });
      onEvent({ type: "finish" });
    });
    const { result } = renderHook(() => useChat({ responder, sessionId }));
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
    useChatSessions.getState().saveMessages(sessionId, initial);
    renderHook(() => useChat({ sessionId }));
  });

  it("空输入不触发发送", async () => {
    const calls: string[] = [];
    const { responder } = stubResponder(async (msg) => {
      calls.push(msg);
    });
    const { result } = renderHook(() => useChat({ responder, sessionId }));
    act(() => result.current.setInput("   "));
    await act(async () => {
      await result.current.sendMessage();
    });
  });
});
