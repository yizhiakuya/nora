import { describe, it } from "vitest";
import { CHAT_COMMANDS, matchingChatCommands, parseChatCommand } from "./chatCommands";
import { estimateChatContextTokens } from "./chatContext";
import { applyChatStreamEvent, type ChatMessage } from "./api/chatApi";

describe("Agent 命令冒烟", () => {
  it("精确命令、参数、未知文本和中文筛选", () => {
    for (const command of CHAT_COMMANDS) {
      parseChatCommand(`/${command.name}`);
      matchingChatCommands(command.name);
    }
    for (const input of ["/compact 保留未完成任务\n和约束", "  /CONTEXT  ", "/tmp/report.md", "/compactish", "正文 /compact", "https://example.com", "`/compact`", ""]) parseChatCommand(input);
    for (const query of ["", "压缩", "权限", "token", "不存在"]) matchingChatCommands(query);
  });

  it("摘要请求输出不进入工作上下文，发布检查点后替换基线", () => {
    const previous: ChatMessage = { id: "old", role: "assistant", content: "完成", timestamp: "12:00",
      contextUsage: { promptTokens: 20_000, outputTokens: 200, contextWindow: 128_000, roundIndex: 1, source: "upstream" } };
    let compact: ChatMessage = { id: "compact", role: "assistant", sender: "compaction", content: "/compact 保留约束", timestamp: "12:01", steps: [] };
    compact = { ...compact, ...applyChatStreamEvent(compact, { type: "reasoning_delta", roundIndex: 1, content: "生成摘要".repeat(1000) }) };
    estimateChatContextTokens([previous, compact]);
    compact = { ...compact, ...applyChatStreamEvent(compact, { type: "context_usage", contextUsage: { promptTokens: 8_000, contextWindow: 128_000, roundIndex: 0, source: "estimate" } }) };
    estimateChatContextTokens([previous, compact]);
    estimateChatContextTokens([previous, JSON.parse(JSON.stringify(compact))]);
  });
});
