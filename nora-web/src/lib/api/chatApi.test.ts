import { describe, it } from "vitest";
import { applyChatStreamEvent, createChatStreamBuffer, reconcileChatHistory, splitChatMessages, type ChatMessage, type ChatStreamEvent } from "./chatApi";

describe("chat stream messages", () => {
  it("历史回填与对话变更的渲染身份冒烟", () => {
    const current: ChatMessage[] = [
      { id: "local-user", role: "user", content: "计算", timestamp: "11:00" },
      { id: "live-session", role: "assistant", content: "正在计算", timestamp: "11:00" },
    ];
    const history: ChatMessage[] = [
      { ...current[0], id: "server-user" },
      { ...current[1], id: "server-assistant", content: "结果：43" },
    ];
    reconcileChatHistory(current, history);
    reconcileChatHistory(current, [...history, { id: "next-user", role: "user", content: "继续", timestamp: "11:01" }]);
    reconcileChatHistory(current, [{ ...history[0], content: "已编辑的问题" }, history[1]]);
    reconcileChatHistory(current, [{ ...history[0], role: "assistant" }, history[1]]);
  });

  it("多次插入、原位工具更新与历史分段冒烟", () => {
    const user: ChatMessage = { id: "inserted", role: "user", sender: "steering", content: "补充要求", timestamp: "11:01" };
    let assistant: ChatMessage = { id: "live", role: "assistant", content: "", timestamp: "11:00", isTyping: true, steps: [] };
    const events: ChatStreamEvent[] = [
      { type: "context_usage", contextUsage: { promptTokens: 12_000, contextWindow: 128_000, roundIndex: 1 } },
      { type: "reasoning_delta", roundIndex: 1, content: "检查页面" },
      { type: "step", step: { id: "tool", type: "tool", title: "打开页面", toolName: "browse_web", status: "running" } },
      { type: "step", step: { id: user.id, type: "context", title: "用户补充", detail: user.content, status: "completed", context: { kind: "user-input" } } },
      { type: "step", step: { id: "tool", type: "tool", title: "打开页面", status: "completed" } },
      { type: "reasoning_delta", roundIndex: 2, content: "按补充要求处理" },
      { type: "context_usage", contextUsage: { promptTokens: 14_000, contextWindow: 128_000, roundIndex: 2 } },
      { type: "step", step: { id: "inserted-again", type: "context", title: "用户补充", detail: "再补充", status: "completed", context: { kind: "user-input" } } },
      { type: "step", step: { id: "answer", type: "answer", title: "结果", detail: "已完成", status: "completed" } },
      { type: "step", step: { id: "pending", type: "tool", title: "等待审批", toolName: "manage_workspace", status: "pending" } },
      { type: "finish", turnMetrics: { durationMs: 1000, usage: null } },
    ];
    for (const event of events) {
      assistant = { ...assistant, ...applyChatStreamEvent(assistant, event) };
      splitChatMessages([assistant]);
      splitChatMessages([assistant, user]);
      splitChatMessages([user, assistant]);
    }
    const conversation = splitChatMessages([assistant, user]);
    reconcileChatHistory([assistant, user], conversation.map((message, index) => ({ ...message, id: `stored-${index}` })));
  });

  it("执行过程、交互票据与终态更新冒烟", () => {
    let message: Partial<ChatMessage> = { content: "", steps: [] };
    const events: ChatStreamEvent[] = [
      { type: "reasoning_delta", roundIndex: 1, content: "检查页面" },
      { type: "reasoning_delta", roundIndex: 1, content: "并选择链接" },
      { type: "delta", roundIndex: 1, content: "开始采集" },
      { type: "delta", roundIndex: 1, content: "资料" },
      { type: "approval_required", approval: { approvalToken: "a", stepId: "tool", actionType: "browser", target: "page", summary: "打开页面", risk: "high" } },
      { type: "step", step: { id: "tool", type: "tool", title: "打开页面", toolName: "browse_web", status: "running" } },
      { type: "question_required", question: { questionToken: "q", stepId: "question", question: "选择范围" } },
      { type: "step", step: { id: "question", type: "tool", title: "选择范围", status: "running" } },
      { type: "step", step: { id: "question", type: "tool", title: "选择范围", status: "completed" } },
      { type: "sources", sources: [] },
      { type: "delta", content: "已完成" },
      { type: "step", step: { id: "answer", type: "answer", title: "结果", detail: "已完成", status: "completed" } },
      { type: "finish", turnMetrics: { durationMs: 1000, usage: null } },
    ];
    for (const event of events) message = { ...message, ...applyChatStreamEvent(message, event) };
    applyChatStreamEvent(message, { type: "finish", stopped: true });
    applyChatStreamEvent(message, { type: "finish", failed: true });
    applyChatStreamEvent(message, { type: "snapshot", message: { content: "恢复正文", steps: message.steps,
      sources: [], approval: undefined, question: undefined, contextUsage: undefined } });
  });

  it("文本批处理、轮次切换与快照恢复冒烟", async () => {
    let message: Partial<ChatMessage> = {};
    const buffer = createChatStreamBuffer(event => {
      message = { ...message, ...applyChatStreamEvent(message, event) };
    });
    for (let i = 0; i < 10_000; i++) buffer.push({ type: "reasoning_delta", roundIndex: 1, content: "思考" });
    await new Promise(resolve => setTimeout(resolve, 60));
    buffer.push({ type: "reasoning_delta", roundIndex: 1, content: "补充" });
    buffer.push({ type: "delta", roundIndex: 1, content: "正文" });
    buffer.push({ type: "delta", roundIndex: 2, content: "第二轮" });
    buffer.push({ type: "snapshot", message: { content: "", steps: message.steps,
      sources: [], approval: undefined, question: undefined, contextUsage: { promptTokens: 5000, contextWindow: 128000, roundIndex: 2 } } });
    buffer.push({ type: "delta", content: "最终回答" });
    buffer.push({ type: "finish" });
    buffer.flush();
  });
});
