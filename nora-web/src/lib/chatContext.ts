import type { ChatContextUsage, ChatMessage, ChatStep } from "./api/chatApi";
import { estimateTextTokens } from "./tokenEstimate";

function stepTokens(step: ChatStep): number {
  if (step.type === "tool") {
    return (step.inputTokens ?? estimateTextTokens(JSON.stringify(step.input ?? {})))
      + estimateTextTokens(step.result?.content ?? step.result?.error ?? "");
  }
  if (step.type === "commentary") return estimateTextTokens(step.detail ?? "");
  if (step.type === "context") return step.context?.kind === "user-input" ? estimateTextTokens(step.detail ?? "")
    : (step.context?.files ?? []).reduce((sum, file) => sum + estimateTextTokens(file.content ?? ""), 0);
  return 0;
}

export function estimateChatMessageTokens(message: Partial<ChatMessage>, fromRound?: number): number {
  if (message.sender === "compaction") return 0;
  return estimateTextTokens(message.content ?? "") + (message.steps ?? [])
    .filter(step => fromRound == null || (step.roundIndex ?? 0) >= fromRound)
    .reduce((sum, step) => sum + stepTokens(step), 0);
}

export interface ContextMeter {
  tokens: number;
  limit: number;
  windowConfigured: boolean;
  estimated: boolean;
  usage?: ChatContextUsage;
}

/** 最近请求基线 + 此后收到的内容；新请求替换基线，不能累加计费 usage。 */
export function estimateChatContextTokens(messages: ChatMessage[]): number {
  const measured = messages.findLastIndex(message => message.role === "assistant"
    && (message.contextUsage?.promptTokens != null || message.turnMetrics?.promptTokens != null));
  if (measured < 0) return messages.filter(message => message.sender !== "compaction").reduce((sum, message) => sum + estimateChatMessageTokens(message) + 8, 0);
  const message = messages[measured];
  const round = message.contextUsage?.roundIndex ?? Math.max(0, ...(message.steps ?? []).map(step => step.roundIndex ?? 0));
  const received = message.contextUsage?.receivedTokens;
  const generated = received != null ? Math.max(0, estimateChatMessageTokens(message) - received)
    : estimateChatMessageTokens(message, round);
  const embeddedUsers = new Set((message.steps ?? []).filter(step => step.context?.kind === "user-input").map(step => step.id));
  return (message.contextUsage?.promptTokens ?? message.turnMetrics?.promptTokens ?? 0)
    + generated
    + messages.slice(measured + 1).filter(item => item.sender !== "compaction" && !embeddedUsers.has(item.id))
      .reduce((sum, item) => sum + estimateChatMessageTokens(item) + 8, 0);
}

export function resolveChatContextLimit(messages: ChatMessage[], configured = 128_000): number {
  const measured = messages.findLast(message => message.role === "assistant"
    && (message.contextUsage?.contextWindow != null || message.turnMetrics?.contextWindow != null));
  return measured?.contextUsage?.contextWindow ?? measured?.turnMetrics?.contextWindow ?? configured;
}
