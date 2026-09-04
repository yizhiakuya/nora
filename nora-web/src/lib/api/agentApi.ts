import type { ChatMessage, ChatResponder, ChatStep } from "./chatApi";
import { API_BASE } from "./client";
import type { Citation } from "@/types";
import { parseSSEStream } from "./sse";

interface StepPayload {
  id?: string;
  type?: "think" | "tool";
  title?: string;
  detail?: string;
  duration?: string | number;
  status?: ChatStep["status"];
}

interface DeltaPayload {
  content?: string;
}

interface DonePayload {
  messageId?: string;
}

function normalizeStep(step: StepPayload, index: number): ChatStep {
  const duration =
    typeof step.duration === "number" ? `${(step.duration / 1000).toFixed(2)}s` : step.duration;

  return {
    id: step.id ?? String(index + 1),
    type: step.type ?? "tool",
    title: step.title ?? "Agent 执行",
    detail: step.detail,
    duration,
    status: step.status ?? "running",
  };
}

function parseData<T>(data: string): T | undefined {
  if (!data) return undefined;
  try {
    return JSON.parse(data) as T;
  } catch (error) {
    throw new Error(`Invalid SSE JSON payload: ${data}`, { cause: error });
  }
}

function normalizeSources(sources: Citation[] | undefined): Citation[] | undefined {
  if (!Array.isArray(sources)) return undefined;
  return sources.map((source) => ({
    docName: source?.docName ?? "",
    source: source?.source,
    chunkIndex: source?.chunkIndex ?? 0,
    score: source?.score ?? 0,
    snippet: source?.snippet ?? "",
  }));
}

export const AgentAPI: { sendMessage: ChatResponder } = {
  async sendMessage(message, onUpdate, sessionId) {
    if (!sessionId) {
      throw new Error("Agent API requires a sessionId");
    }

    let response: Response;
    try {
      response = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: message }),
      });
    } catch (error) {
      throw new Error("Cannot connect to agent-service", { cause: error });
    }

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `Agent API HTTP ${response.status}`);
    }

    const steps: ChatStep[] = [];
    let content = "";

    await parseSSEStream(response.body.getReader(), ({ event, data }) => {
      if (event === "step") {
        const payload = parseData<StepPayload>(data);
        if (!payload) return;
        steps.push(normalizeStep(payload, steps.length));
        onUpdate({ steps: [...steps] });
      } else if (event === "delta") {
        const payload = parseData<DeltaPayload>(data);
        if (!payload?.content) return;
        content += payload.content;
        onUpdate({ content });
      } else if (event === "sources") {
        const payload = parseData<Citation[]>(data);
        const sources = normalizeSources(payload);
        if (sources) onUpdate({ sources });
      } else if (event === "done") {
        parseData<DonePayload>(data);
        onUpdate({ isTyping: false });
      }
    });

    if (!content && steps.length === 0) {
      throw new Error("Agent API returned an empty stream");
    }

    onUpdate({ isTyping: false });
  },
};
