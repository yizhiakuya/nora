import { useEffect, useMemo, useRef, useState } from "react";
import type { ChatMessage } from "@/lib/api/chatApi";
import { estimateChatContextTokens, resolveChatContextLimit, type ContextMeter } from "@/lib/chatContext";
import { estimateTextTokens } from "@/lib/tokenEstimate";
import { resolveDefaultProvider, useModelProviders } from "./useModelProviders";
import { parseChatCommand } from "@/lib/chatCommands";

/** 大段推理每秒采样一次，新请求和结束立即更新。 */
export function useContextMeter(messages: ChatMessage[], input: string, active: boolean): ContextMeter {
  const latest = useRef(messages);
  useEffect(() => { latest.current = messages; }, [messages]);
  const last = messages.findLast(message => message.role === "assistant");
  const configured = useModelProviders(state => resolveDefaultProvider(state.providers, state.defaultModel, state.defaultProviderId)
    ?.modelSettings?.[state.defaultModel]?.contextWindow);
  const [sampled, setSampled] = useState(() => estimateChatContextTokens(messages));
  useEffect(() => {
    setSampled(estimateChatContextTokens(latest.current));
    if (!active) return;
    const timer = setInterval(() => setSampled(estimateChatContextTokens(latest.current)), 1_000);
    return () => clearInterval(timer);
  }, [active, last?.id, last?.contextUsage, last?.turnMetrics]);
  const idle = useMemo(() => active ? 0 : estimateChatContextTokens(messages), [active, messages]);
  const tokens = (active ? sampled : idle) + (parseChatCommand(input) ? 0 : estimateTextTokens(input));
  const usage = last?.contextUsage ?? (last?.sender === "compaction" ? messages.findLast(message => message.contextUsage)?.contextUsage : undefined);
  return { tokens,
    limit: active ? resolveChatContextLimit(messages, configured ?? 128_000) : configured ?? 128_000,
    windowConfigured: active && usage ? usage.windowConfigured === true : configured != null,
    estimated: usage?.source !== "upstream" || usage.outputTokens == null || tokens !== usage.promptTokens + usage.outputTokens,
    usage,
  };
}
