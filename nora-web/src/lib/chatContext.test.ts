import { describe, it } from "vitest";
import { applyChatStreamEvent, type ChatMessage } from "./api/chatApi";
import { estimateChatContextTokens, resolveChatContextLimit } from "./chatContext";
import { formatTokenCount } from "./tokenEstimate";

describe("上下文计量冒烟", () => {
  it("新请求替换基线、生成增量、压缩和历史恢复", () => {
    let message: ChatMessage = { id: "assistant", role: "assistant", content: "", timestamp: "11:00", steps: [] };
    for (const event of [
      { type: "context_usage" as const, contextUsage: { promptTokens: 12_000, contextWindow: 128_000, roundIndex: 1 } },
      { type: "reasoning_delta" as const, roundIndex: 1, content: "检查资料".repeat(100) },
      { type: "context_usage" as const, contextUsage: { promptTokens: 11_000, contextWindow: 128_000, roundIndex: 1, source: "upstream" as const, outputTokens: 700, cacheReadInputTokens: 10_000 } },
      { type: "step" as const, step: { id: "call-1", type: "tool" as const, status: "completed" as const, title: "读文件", roundIndex: 1, inputTokens: 20, result: { content: "真实结果" } } },
      { type: "context_usage" as const, contextUsage: { promptTokens: 8_000, contextWindow: 128_000, roundIndex: 2 } },
      { type: "reasoning_delta" as const, roundIndex: 2, content: "继续核实" },
      { type: "delta" as const, roundIndex: 2, content: "完成" },
    ]) {
      message = { ...message, ...applyChatStreamEvent(message, event) };
      estimateChatContextTokens([message]);
      resolveChatContextLimit([message]);
    }
    estimateChatContextTokens([{ ...message, contextUsage: undefined, turnMetrics: { usage: null, promptTokens: 8_000, contextWindow: 128_000 } }]);
    estimateChatContextTokens([{ ...message, contextUsage: undefined }]);
    resolveChatContextLimit([], 32_000);
    for (const value of [0, 41, 999, 1_000, 1_100, 118_799]) formatTokenCount(value);
  });
});
