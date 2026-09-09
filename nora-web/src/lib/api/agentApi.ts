import type { ChatMessage, ChatResponder, ChatStep, ApprovalRequest, PermissionMode } from "./chatApi";
import { API_BASE, defaultTimeoutSignal } from "./client";
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
  target?: string;
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
  contextWindow?: number | null;
  promptTokens?: number | null;
  ttftMs?: number | null;
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

export async function resolveApproval(
  sessionId: string,
  approvalToken: string,
  approved: boolean
): Promise<boolean> {
  const response = await fetch(
    `${API_BASE}/chat/approvals/${encodeURIComponent(approvalToken)}?sessionId=${encodeURIComponent(sessionId)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ approved }),
    }
  );
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `审批请求失败(HTTP ${response.status})`);
  }
  return approved;
}

export const AgentAPI: { sendMessage: ChatResponder } = {
  async sendMessage(message, onUpdate, sessionId, model, reasoningLevel, permissionMode, signal) {
    if (!sessionId) {
      throw new Error("Agent API requires a sessionId");
    }

    let response: Response;
    try {
      response = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          content: message,
          model,
          reasoningLevel: reasoningLevel || null,
          permissionMode: permissionMode ?? "assist",
        }),
        // 请求阶段可被「停止」按钮中断;流阶段由 reader.cancel 兜底
        signal,
      });
    } catch (error) {
      // 用户主动停止:以正常结果结束(部分文本已由 onUpdate 流出),不按错误处理
      if (signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) {
        return;
      }
      throw new Error("Cannot connect to agent-service", { cause: error });
    }

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `Agent API HTTP ${response.status}`);
    }

    const steps: ChatStep[] = [];
    let content = "";
    let donePayload: DonePayload | undefined;
    /** 当前挂起审批:SSE approval_required 下发;同 stepId 的工具步骤到达即已决策,卡片应清除 */
    let pendingApproval: ApprovalRequest | undefined;

    // 流结束兜底:仍在 running 的步骤(中断/异常残留)就地收尾,避免永久「执行中」。
    // 用户主动停止:推理步骤记 completed(已流出的部分思考保留、折叠回看,配合
    // 消息级「已停止」标记);真实失败路径(流错误/网络断)仍记 failed(红色错误行)。
    const flushPendingSteps = () => {
      const aborted = !!signal?.aborted;
      let touched = false;
      for (let i = 0; i < steps.length; i++) {
        if (steps[i].status === "running") {
          const keepPartial = aborted && steps[i].type === "think";
          steps[i] = {
            ...steps[i],
            status: keepPartial ? "completed" : "failed",
            detail: steps[i].detail ?? (keepPartial ? "" : "流中断,未收到该步骤结果"),
          };
          touched = true;
        }
      }
      if (touched) onUpdate({ steps: [...steps] });
    };

    await parseSSEStream(response.body.getReader(), ({ event, data }) => {
      // 用户已停止:退出事件处理(SSE 解析循环由 signal 监听终止)
      if (signal?.aborted) return;
      if (event === "approval_required") {
        const approval = parseData<ApprovalRequest>(data);
        if (approval?.approvalToken) {
          pendingApproval = approval;
          onUpdate({ approval });
        }
      } else if (event === "step") {
        const payload = parseData<StepPayload>(data);
        if (!payload) return;
        const normalized = normalizeStep(payload, steps.length);
        // 工具步骤(重新)出现 = 该调用已越过审批门(批准执行/拒绝跳过),
        // 挂起的审批卡同步撤下,避免 120s 超时后还挂着死卡让用户白点
        if (pendingApproval && normalized.id === pendingApproval.stepId) {
          pendingApproval = undefined;
          onUpdate({ approval: undefined });
        }
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
          approval: undefined,
          steps: [...steps],
          turnMetrics: donePayload?.durationMs != null
            ? {
                durationMs: donePayload.durationMs,
                usage: donePayload.usage ?? null,
                contextWindow: donePayload.contextWindow ?? null,
                promptTokens: donePayload.promptTokens ?? null,
                ttftMs: donePayload.ttftMs ?? null,
              }
            : undefined,
        });
      } else if (event === "error") {
        const payload = parseData<ErrorPayload>(data);
        flushPendingSteps();
        onUpdate({ error: payload?.message || "Agent 执行失败", isTyping: false, approval: undefined });
        throw new Error(payload?.message || "Agent 执行失败");
      }
    }, signal);

    flushPendingSteps();

    // 用户停止:保留已流出的部分内容,正常结束(stopped 标记由 useChat 层加)
    if (signal?.aborted) {
      onUpdate({ isTyping: false });
      return;
    }

    if (!content && steps.length === 0) {
      throw new Error("Agent API returned an empty stream");
    }

    onUpdate({ isTyping: false });
  },
};

/** POST /chat/sessions/{id}/cancel → 中断进行中的轮次(上游 LLM 调用一并中止) */
export async function cancelTurnOnBackend(sessionId: string): Promise<void> {
  try {
    await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, { method: "POST" });
  } catch {
    /* 网络失败时前端 abort 已断流,后端轮次自然结束即可 */
  }
}

/** GET /chat/sessions → 会话摘要列表(最近活跃在前) */
export async function fetchSessions(): Promise<
  { id: string; title: string; messageCount: number; createdAt: string; lastActivity?: string }[]
> {
  const res = await fetch(`${API_BASE}/chat/sessions`, { signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchSessions failed: ${res.status}`);
  const body = await res.json();
  const list = (body?.data ?? body) as Array<{
    id: string;
    title: string;
    messageCount: number;
    createdAt: string;
    lastActivity?: string;
  }>;
  return Array.isArray(list) ? list : [];
}

/** GET /chat/sessions/{id}/messages → 完整消息历史(steps 合并后) */
/** 后端 created_at 是本地挂钟时间的 ISO 串(无时区,如 2026-09-09T11:38:12);
 *  直接 new Date() 在部分浏览器会把无时区串按 UTC 解析,这里手动拆解保本地语义。 */
function toHm(raw?: string): string {
  if (!raw) return "";
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(raw);
  if (m) return `${m[4]}:${m[5]}`;
  const d = new Date(raw);
  return isNaN(d.getTime())
    ? ""
    : d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
}

export async function fetchSessionMessages(sessionId: string): Promise<ChatMessage[]> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages`, { signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchSessionMessages failed: ${res.status}`);
  const stored = (await res.json()) as Array<{
    role: "user" | "assistant";
    content: string;
    steps?: StepPayload[];
    sources?: Citation[];
    createdAt?: string;
  }>;
  return (stored ?? []).map((m, i) => ({
    id: `${sessionId}-${i}`,
    role: m.role,
    content: m.content ?? "",
    timestamp: toHm(m.createdAt),
    steps: (m.steps ?? []).map((s, j) => normalizeStep(s, j)),
    sources: normalizeSources(m.sources),
  }));
}

/** DELETE /chat/sessions/{id} → 删除会话及消息 */
export async function deleteSessionOnBackend(sessionId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE", signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`deleteSession failed: ${res.status}`);
}

/**
 * DELETE /chat/sessions/{id}/messages/{index} → 从第 index 条(含)起截断历史。
 * 「编辑重发」用:回退到某条用户消息改完重发前,先删掉旧分支,
 * 服务端 LLM 上下文才不会残留已被 UI 丢弃的消息。
 */
export async function truncateMessagesFrom(sessionId: string, index: number): Promise<void> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages/${index}`, { method: "DELETE", signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`truncateMessagesFrom failed: ${res.status}`);
}

/** GET /chat/settings → agent 全局设置(权限模式/默认模型/思考等级覆写) */
export async function fetchAgentSettings(): Promise<{
  permissionMode?: "ask" | "assist" | "full";
  model?: string | null;
  reasoningLevel?: string | null;
}> {
  const res = await fetch(`${API_BASE}/chat/settings`, { signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchAgentSettings failed: ${res.status}`);
  const body = await res.json();
  return (body?.data ?? body) ?? {};
}

/** PUT /chat/settings → 部分更新 agent 全局设置(merge 语义) */
export async function saveAgentSettings(patch: {
  permissionMode?: "ask" | "assist" | "full";
  model?: string | null;
  reasoningLevel?: string | null;
}): Promise<void> {
  const res = await fetch(`${API_BASE}/chat/settings`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
    signal: defaultTimeoutSignal(),
  });
  if (!res.ok) throw new Error(`saveAgentSettings failed: ${res.status}`);
}
