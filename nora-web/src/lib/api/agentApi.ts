import type { ChatMessage, ChatResponder, ChatStep } from "./chatApi";
import { API_BASE } from "./client";
import type { Citation } from "@/types";
import { parseSSEStream } from "./sse";

/** 结构化工具结果(后端 ChatStepDto.StepResult) */
interface StepResultPayload {
  content?: string;
  summary?: string;
  rowCount?: number;
  lineCount?: number;
  truncated?: boolean;
  error?: string;
}

/** 结构化工具入参(后端 ChatStepDto.StepInput) */
interface StepInputPayload {
  sql?: string;
  service?: string;
  limit?: number;
}

interface StepPayload {
  id?: string;
  type?: "think" | "tool";
  title?: string;
  detail?: string;
  duration?: string | number;
  status?: ChatStep["status"];
  toolName?: string;
  input?: StepInputPayload;
  result?: StepResultPayload;
  roundIndex?: number;
}

interface ReasoningDeltaPayload {
  roundIndex?: number;
  content?: string;
}

interface DeltaPayload {
  content?: string;
}

interface DoneUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

interface DonePayload {
  messageId?: string;
  durationMs?: number;
  usage?: DoneUsage | null;
  answerChars?: number;
}
interface ErrorPayload { message?: string }

function normalizeStep(step: StepPayload, index: number): ChatStep {
  const title = step.title === "调用工具 execute_sql" ? "查询数据库" : step.title === "调用工具 read_service_logs" ? "读取服务日志" : step.title;
  const duration =
    typeof step.duration === "number" ? `${(step.duration / 1000).toFixed(2)}s` : step.duration;

  return {
    id: step.id ?? String(index + 1),
    type: step.type ?? "tool",
    title: title ?? "Agent 执行",
    detail: step.detail,
    duration,
    status: step.status ?? "running",
    toolName: step.toolName,
    input: step.input,
    result: step.result,
    roundIndex: step.roundIndex,
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
  async sendMessage(message, onUpdate, sessionId, model, reasoningLevel) {
    if (!sessionId) {
      throw new Error("Agent API requires a sessionId");
    }

    let response: Response;
    try {
      response = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: message, model, reasoningLevel: reasoningLevel || null }),
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
    let donePayload: DonePayload | undefined;

    // 流结束兜底:仍在 running 的步骤(中断/异常残留)标记为 failed,避免永久「执行中」
    const flushPendingSteps = () => {
      let touched = false;
      for (let i = 0; i < steps.length; i++) {
        if (steps[i].status === "running") {
          steps[i] = { ...steps[i], status: "failed", detail: steps[i].detail ?? "流中断,未收到该步骤结果" };
          touched = true;
        }
      }
      if (touched) onUpdate({ steps: [...steps] });
    };

    await parseSSEStream(response.body.getReader(), ({ event, data }) => {
      if (event === "step") {
        const payload = parseData<StepPayload>(data);
        if (!payload) return;
        const normalized = normalizeStep(payload, steps.length);
        const existingIndex = steps.findIndex((step) => step.id === normalized.id);
        if (existingIndex >= 0) steps[existingIndex] = normalized;
        else steps.push(normalized);
        onUpdate({ steps: [...steps] });
    } else if (event === "delta") {
        const payload = parseData<DeltaPayload>(data);
        if (!payload?.content) return;
        if (!content) {
          let reasoningCompleted = false;
          for (let i = 0; i < steps.length; i++) {
            if (steps[i].id.startsWith("s-reasoning-") && steps[i].status === "running") {
              steps[i] = { ...steps[i], status: "completed" };
              reasoningCompleted = true;
            }
          }
          if (reasoningCompleted) onUpdate({ steps: [...steps] });
        }
        content += payload.content;
        onUpdate({ content });
      } else if (event === "reasoning_delta") {
        const payload = parseData<ReasoningDeltaPayload>(data);
        if (!payload?.content) return;
        const id = `s-reasoning-${payload.roundIndex ?? "final"}`;
        const index = steps.findIndex((step) => step.id === id);
        if (index >= 0) {
          steps[index] = {
            ...steps[index],
            detail: `${steps[index].detail ?? ""}${payload.content}`,
            status: "running",
          };
        } else {
          steps.push(normalizeStep({
            id,
            type: "think",
            title: "推理过程",
            detail: payload.content,
            status: "running",
            roundIndex: payload.roundIndex,
          }, steps.length));
        }
        onUpdate({ steps: [...steps] });
      } else if (event === "sources") {
        const payload = parseData<Citation[]>(data);
        const sources = normalizeSources(payload);
        if (sources) onUpdate({ sources });
      } else if (event === "done") {
        donePayload = parseData<DonePayload>(data);
        for (let i = 0; i < steps.length; i++) {
          if (steps[i].id.startsWith("s-reasoning-") && steps[i].status === "running") {
            steps[i] = { ...steps[i], status: "completed" };
          }
        }
        onUpdate({
          isTyping: false,
          steps: [...steps],
          turnMetrics: donePayload?.durationMs != null
            ? { durationMs: donePayload.durationMs, usage: donePayload.usage ?? null }
            : undefined,
        });
      } else if (event === "error") {
        const payload = parseData<ErrorPayload>(data);
        flushPendingSteps();
        onUpdate({ error: payload?.message || "Agent 执行失败", isTyping: false });
        throw new Error(payload?.message || "Agent 执行失败");
      }
    });

    flushPendingSteps();

    if (!content && steps.length === 0) {
      throw new Error("Agent API returned an empty stream");
    }

    onUpdate({ isTyping: false });
  },
};
