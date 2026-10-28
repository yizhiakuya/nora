import type { ChatMessage, ChatResponder, ChatStep, ChatStepResult, ChatContextUsage, ApprovalRequest, QuestionRequest, TaskContextPayload } from "./chatApi";
import type { ChatStreamEvent } from "./chatApi";
import { emitSessionTitle } from "./sessionTitleEvents";
import { API_BASE, ApiError, requestJson } from "./client";
import { authHeaders, handleUnauthorized, withAuthToken } from "@/lib/auth";
import type { AgentSettings, Citation, QueuedChatMessage } from "@/types";
import { parseSSEStream } from "./sse";
import { cached, invalidateForPath } from "./requestCache";
import { hmFromLocalIso } from "@/lib/format";
import { estimateTextTokens } from "@/lib/tokenEstimate";

export interface SessionProject {
  projectRoot: string;
  platform: string;
  environment: string;
  rules: { path: string; truncated: boolean; missing: boolean }[];
}

export interface ExecutionModeSettings { executionMode: "standard" | "ptc"; ptcAvailable: boolean }
export const fetchExecutionMode = (id: string) => requestJson<ExecutionModeSettings>(`/chat/sessions/${encodeURIComponent(id)}/execution-mode`);
export const saveExecutionMode = (id: string, executionMode: "standard" | "ptc") => requestJson<ExecutionModeSettings>(`/chat/sessions/${encodeURIComponent(id)}/execution-mode`, {
  method: "POST", body: JSON.stringify({ executionMode }),
});

export const fetchSessionProject = (id: string) => requestJson<SessionProject>(`/chat/sessions/${encodeURIComponent(id)}/project`);
export interface ProjectDirectories {
  path: string;
  parent: string | null;
  defaultRoot: string;
  roots: string[];
  directories: { name: string; path: string }[];
  truncated: boolean;
}
export const fetchProjectDirectories = (id: string, path: string) => requestJson<ProjectDirectories>(
  `/chat/sessions/${encodeURIComponent(id)}/project/directories?path=${encodeURIComponent(path)}`);
export const bindSessionProject = (id: string, projectRoot: string) => requestJson<SessionProject>(`/chat/sessions/${encodeURIComponent(id)}/project`, {
  method: "POST", body: JSON.stringify({ projectRoot }),
});

export interface ChatQueueView { messages: QueuedChatMessage[]; paused: boolean; latestRunId: string | null }
export const fetchChatQueue = (id: string) => requestJson<ChatQueueView>(`/chat/sessions/${encodeURIComponent(id)}/queue`);
export const enqueueChatInput = (id: string, message: QueuedChatMessage) => requestJson<ChatQueueView>(`/chat/sessions/${encodeURIComponent(id)}/queue`, {
  method: "POST", body: JSON.stringify(message),
});
export const withdrawChatInput = (id: string, messageId: string) => requestJson<QueuedChatMessage>(`/chat/sessions/${encodeURIComponent(id)}/queue/${encodeURIComponent(messageId)}`, { method: "DELETE" });
export const insertChatInput = (id: string, messageId: string, turnId: string) => requestJson<boolean>(`/chat/sessions/${encodeURIComponent(id)}/queue/${encodeURIComponent(messageId)}/insert`, {
  method: "POST", body: JSON.stringify({ turnId }),
});
export const resumeChatQueue = (id: string) => requestJson<boolean>(`/chat/sessions/${encodeURIComponent(id)}/queue/resume`, { method: "POST" });

/** 结构化工具结果(后端 ChatStepDto.StepResult) */
type StepResultPayload = ChatStepResult;

/** 结构化工具入参(后端 ChatStepDto.StepInput) */
interface StepInputPayload {
  sql?: string;
  service?: string;
  limit?: number;
  target?: string;
  /** 服务端跨轮历史重建用的脱敏原始参数;UI 不用,normalizeStep 剥离 */
  rawArgs?: string;
}

/** 注入上下文元数据(后端 ChatStepDto.ContextInfo) */
interface StepContextPayload {
  form?: string;
  kind?: string;
  files?: { path: string; bytes?: number; truncated?: boolean; missing?: boolean; content?: string }[];
  entries?: { name: string; description?: string; category?: string }[];
  dailyNotes?: string[];
}

interface StepPayload {
  parentStepId?: string;
  callIndex?: number;
  id?: string;
  type?: ChatStep["type"];
  title?: string;
  detail?: string;
  duration?: string | number;
  status?: ChatStep["status"];
  toolName?: string;
  input?: StepInputPayload;
  result?: StepResultPayload;
  context?: StepContextPayload;
  /** 批量任务实时进度(fetch_media;后端 ChatStepDto.StepProgress) */
  progress?: ChatStep["progress"];
  roundIndex?: number;
}

interface DeltaPayload {
  content?: string;
  roundIndex?: number | null;
}

interface DoneUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  cacheReadInputTokens?: number;
  cacheCreationInputTokens?: number;
}

interface DonePayload {
  messageId?: string;
  durationMs?: number;
  usage?: DoneUsage | null;
  answerChars?: number;
  contextWindow?: number | null;
  promptTokens?: number | null;
  ttftMs?: number | null;
  /** 用户主动停止的取消轮(F3;缺省 = 正常完成) */
  stopped?: boolean;
  /** 统一业务终态(F3,2026-09-26):completed/partial/failed/cancelled;旧后端无该字段 */
  status?: string;
  stopReason?: string;
  hadToolErrors?: boolean;
}

/** SSE `title` 事件载荷：AI 异步起好的会话标题（见后端 AgentController.TitlePayload）。 */
interface TitlePayload {
  sessionId?: string;
  title?: string;
}
interface ErrorPayload {
  message?: string;
  /** 异常处理系统:分类/错误码/提示/可重试/链路 ID(旧后端只给 message 时全部 undefined) */
  category?: string;
  errorCode?: string;
  hint?: string;
  retryable?: boolean;
  traceId?: string;
}

export function normalizeStep(step: StepPayload, index: number): ChatStep {
  const title = step.title === "调用工具 execute_sql" ? "执行数据库命令" : step.title === "调用工具 read_service_logs" ? "读取服务日志" : step.title;
  const duration =
    typeof step.duration === "number" ? `${(step.duration / 1000).toFixed(2)}s` : step.duration;
  // rawArgs 是服务端跨轮历史重建字段(脱敏原始参数),UI 各展示位都不用——
  // 剥离后再透传,避免通用工具的 JSON 详情里重复显示参数
  const { rawArgs: _rawArgs, ...displayInput } = step.input ?? {};

  return {
    id: step.id ?? String(index + 1),
    type: step.type ?? "tool",
    title: title ?? "Agent 执行",
    detail: step.detail,
    duration,
    status: step.status ?? "running",
    toolName: step.toolName,
    input: step.input ? { ...displayInput, ...(step.toolName === "execute_program" && _rawArgs ? { program: _rawArgs } : {}) } : undefined,
    inputTokens: step.input?.rawArgs != null ? estimateTextTokens(step.input.rawArgs) : undefined,
    result: step.result,
    context: step.context,
    progress: step.progress,
    roundIndex: step.roundIndex,
    parentStepId: step.parentStepId,
    callIndex: step.callIndex,
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
    // 阶段 A 字段透传(稳定块标识/命中通道);旧历史消息为 undefined
    chunkId: source?.chunkId ?? null,
    matchChannel: source?.matchChannel ?? null,
    vectorScore: source?.vectorScore ?? null,
    keywordScore: source?.keywordScore ?? null,
  }));
}

export async function resolveApproval(
  sessionId: string,
  approvalToken: string,
  approved: boolean
): Promise<boolean> {
  await requestJson<unknown>(`/chat/approvals/${encodeURIComponent(approvalToken)}?sessionId=${encodeURIComponent(sessionId)}`, {
    method: "POST", body: JSON.stringify({ approved }),
  });
  return approved;
}

/** 回答挂起的 ask_user 提问(2026-09-29);答案作为工具结果回填同一轮。 */
export async function answerQuestion(
  sessionId: string,
  questionToken: string,
  answer: string
): Promise<boolean> {
  await requestJson<unknown>(`/chat/answers/${encodeURIComponent(questionToken)}?sessionId=${encodeURIComponent(sessionId)}`, {
    method: "POST", body: JSON.stringify({ answer }),
  });
  return true;
}

/** 两种传输只解码事件；消息合并与节流由 useChat 统一处理。 */
function decodeChatStreamEvent(event: string, data: string): ChatStreamEvent | undefined {
  switch (event) {
    case "snapshot": {
      const p = parseData<{
        content: string; steps: StepPayload[]; sources?: Citation[];
        contextUsage?: ChatContextUsage; approval?: ApprovalRequest; question?: QuestionRequest;
      }>(data);
      if (!p || typeof p.content !== "string" || !Array.isArray(p.steps)) throw new Error("Invalid live turn snapshot");
      return { type: "snapshot", message: {
        content: p.content, steps: p.steps.map(normalizeStep), sources: normalizeSources(p.sources),
        contextUsage: p.contextUsage ?? undefined, approval: p.approval ?? undefined, question: p.question ?? undefined,
      } };
    }
    case "step": {
      const p = parseData<StepPayload>(data);
      if (!p?.id) throw new Error("Invalid Agent step event");
      return { type: "step", step: normalizeStep(p, 0) };
    }
    case "delta":
    case "reasoning_delta": {
      const p = parseData<DeltaPayload>(data);
      if (typeof p?.content === "string" && p.content) return { type: event, content: p.content, roundIndex: p.roundIndex };
      return;
    }
    case "sources": {
      const sources = normalizeSources(parseData<Citation[]>(data));
      if (sources) return { type: "sources", sources };
      return;
    }
    case "context_usage": {
      const p = parseData<ChatContextUsage>(data);
      if (p) return { type: "context_usage", contextUsage: p };
      return;
    }
    case "approval_required": {
      const p = parseData<ApprovalRequest>(data);
      if (p?.approvalToken) return { type: "approval_required", approval: p };
      return;
    }
    case "question_required": {
      const p = parseData<QuestionRequest>(data);
      if (p?.questionToken) return { type: "question_required", question: p };
      return;
    }
    case "done": {
      const p = parseData<DonePayload>(data);
      if (!p || typeof p !== "object") throw new Error("Invalid Agent completion event");
      return {
        type: "finish", stopped: p.stopped, failed: p.status === "failed",
        turnMetrics: p.durationMs != null ? {
          durationMs: p.durationMs, usage: p.usage ?? null, contextWindow: p.contextWindow ?? null,
          promptTokens: p.promptTokens ?? null, ttftMs: p.ttftMs ?? null,
          status: p.status, stopReason: p.stopReason, hadToolErrors: p.hadToolErrors,
        } : undefined,
      };
    }
    case "error": {
      const p = parseData<ErrorPayload>(data);
      return { type: "error", error: new ApiError({
        message: p?.message || "Agent 执行失败", code: 0, category: p?.category,
        errorCode: p?.errorCode, hint: p?.hint, retryable: p?.retryable, traceId: p?.traceId,
      }) };
    }
    case "title": {
      const p = parseData<TitlePayload>(data);
      if (p?.sessionId && p.title) emitSessionTitle(p.sessionId, p.title);
    }
  }
}

function createResponder(compactRequestId?: string): ChatResponder {
  return async (message, onEvent, sessionId, model, _reasoningLevel, _permissionMode, signal, providerId, context) => {
    if (!sessionId) throw new Error("Agent API requires a sessionId");
    let response: Response;
    try {
      response = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/${compactRequestId ? "compact" : "messages"}`, {
        method: "POST", headers: { "Content-Type": "application/json", ...authHeaders() },
        body: JSON.stringify(compactRequestId ? { requestId: compactRequestId, focus: message, model, providerId: providerId ?? null } : {
          content: message, model, providerId: providerId ?? null, context: context ?? null,
        }),
        // 切页只断开连接；停止执行使用后端取消接口，继续接收终态。
        signal,
      });
    } catch (error) {
      if (signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) return;
      throw new Error("Cannot connect to agent-service", { cause: error });
    }
    if (response.status === 401) handleUnauthorized();
    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `Agent API HTTP ${response.status}`);
    }
    invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
    let receivedDone = false;
    let receivedContent = false;
    let streamError: Error | undefined;
    await parseSSEStream(response.body.getReader(), ({ event, data }) => {
      if (signal?.aborted) return;
      const decoded = decodeChatStreamEvent(event, data);
      if (!decoded) return;
      if (decoded.type === "finish") receivedDone = true;
      else if (decoded.type === "error") streamError = decoded.error;
      else if (decoded.type === "step" || decoded.type === "delta" || decoded.type === "reasoning_delta"
        || (decoded.type === "snapshot" && (decoded.message.content || decoded.message.steps?.length))) receivedContent = true;
      onEvent(decoded);
    }, signal);
    if (signal?.aborted) return;
    if (streamError) throw streamError;
    if (!receivedDone) throw new Error("Agent stream ended before completion");
    if (!receivedContent) throw new Error("Agent API returned an empty stream");
  };
}

export const AgentAPI = { sendMessage: createResponder(), compact: createResponder };

/** POST /chat/sessions/{id}/cancel → 中断进行中的轮次(上游 LLM 调用一并中止) */
export async function cancelTurnOnBackend(sessionId: string): Promise<boolean> {
  return requestJson<boolean>(`/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, { method: "POST" });
}

export interface SessionSummary {
  id: string;
  title: string;
  titleGenerated?: boolean;
  messageCount: number;
  createdAt: string;
  lastActivity?: string;
  origin?: string | null;
}

export function fetchSessions(force = false): Promise<SessionSummary[]> {
  return cached("/chat/sessions", 30_000, force,
    () => requestJson<SessionSummary[]>("/chat/sessions", { cache: "no-store" }));
}

export interface SessionPage { items: SessionSummary[]; hasMore: boolean }

export function searchSessions(query: string, offset = 0, limit = 30, signal?: AbortSignal): Promise<SessionPage> {
  const params = new URLSearchParams({ q: query, offset: String(offset), limit: String(limit) });
  return requestJson<SessionPage>(`/chat/sessions/search?${params}`, { cache: "no-store", signal });
}

export function fetchSession(id: string): Promise<SessionSummary> {
  return requestJson<SessionSummary>(`/chat/sessions/${encodeURIComponent(id)}`, { cache: "no-store" });
}

export interface PendingAction { kind: "approval" | "question"; sessionId: string; stepId: string; summary: string }

export function fetchPendingActions(): Promise<PendingAction[]> {
  return requestJson<PendingAction[]>("/chat/pending-actions", { cache: "no-store" });
}

export async function steerTurnOnBackend(sessionId: string, turnId: string, messageId: string, content: string, context?: TaskContextPayload): Promise<void> {
  await requestJson<boolean>(`/chat/sessions/${encodeURIComponent(sessionId)}/steer`, {
    method: "POST", body: JSON.stringify({ turnId, messageId, content, context }),
  });
  invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
}

export async function fetchSessionMessages(sessionId: string, force = false): Promise<ChatMessage[]> {
  return cached(
    `/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    0,
    force,
    () => fetchSessionMessagesUncached(sessionId),
  );
}

async function fetchSessionMessagesUncached(sessionId: string): Promise<ChatMessage[]> {
  const stored = await requestJson<Array<{
    id?: string;
    role: "user" | "assistant";
    content: string;
    steps?: StepPayload[];
    sources?: Citation[];
    /** 整轮耗时(ms):assistant 消息落库字段,折叠行「查看工作过程 · Ns」用 */
    durationMs?: number | null;
    /** 当轮 prompt token 估算(done 同源落库);旧数据为 null */
    promptTokens?: number | null;
    /** 当轮生效上下文窗口(落库);旧数据为 null */
    contextWindow?: number | null;
    runStatus?: string | null;
    taskContext?: TaskContextPayload | null;
    contextUsage?: ChatContextUsage | null;
    createdAt?: string;
    /** 发送者(2026-09-20):automation=定时任务发送;旧数据 null 按 user */
    sender?: string | null;
  }>>(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`, { cache: "no-store" });
  return (stored ?? []).map((m, i) => ({
    id: m.id ?? `${sessionId}-${i}`,
    role: m.role,
    content: m.content ?? "",
    // 错误原文来自服务端步骤，部分回答和刷新历史也保持同一展示。
    error: m.role === "assistant"
      ? m.steps?.find(step => step.id === "s-error" && step.status === "failed")?.detail
      : undefined,
    timestamp: hmFromLocalIso(m.createdAt),
    sender: m.sender === "automation" || m.sender === "steering" || m.sender === "compaction" ? m.sender : undefined,
    steps: (m.steps ?? []).map((s, j) => normalizeStep(s, j)),
    sources: normalizeSources(m.sources),
    taskContext: m.taskContext ?? undefined,
    contextUsage: m.contextUsage ?? undefined,
    stopped: m.runStatus === "cancelled",
    // 整轮耗时来自落库字段(与 done 事件同源)。此前只在流式期间有,刷新/切会话
    // 后从历史重建消息就丢了——「查看工作过程 · N 次工具调用 · Xs」的总计时消失。
    // promptTokens/contextWindow 同理(2026-09-19):上下文指示器刷新后显示真实值,
    // 否则回退字符估算严重低估(用户反馈「上下文不准」)。
    // 旧数据无这些字段(undefined/null)→ 保持 undefined,折叠行不显示秒数。
    turnMetrics: m.role === "assistant" && (m.durationMs != null || m.runStatus != null)
      ? {
          durationMs: m.durationMs ?? undefined,
          status: m.runStatus ?? undefined,
          usage: null,
          contextWindow: m.contextWindow ?? null,
          promptTokens: m.promptTokens ?? null,
          ttftMs: null,
        }
      : undefined,
  }));
}

/** DELETE /chat/sessions/{id} → 删除会话及消息 */
export async function deleteSessionOnBackend(sessionId: string): Promise<void> {
  await requestJson<unknown>(`/chat/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
}

/**
 * DELETE /chat/sessions/{id}/messages/{index} → 从第 index 条(含)起截断历史。
 * 「编辑重发」用:回退到某条用户消息改完重发前,先删掉旧分支,
 * 服务端 LLM 上下文才不会残留已被 UI 丢弃的消息。
 */
export async function truncateMessagesFrom(sessionId: string, index: number): Promise<void> {
  await requestJson<unknown>(`/chat/sessions/${encodeURIComponent(sessionId)}/messages/${index}`, { method: "DELETE" });
}

/** GET /chat/sessions/{id}/turn/live → 会话是否有进行中轮次及其已缓冲事件 */
export async function fetchLiveTurn(sessionId: string): Promise<LiveTurnInfo> {
  return requestJson<LiveTurnInfo>(`/chat/sessions/${encodeURIComponent(sessionId)}/turn/live`, { cache: "no-store" });
}

export interface LiveTurnInfo {
  running: boolean;
  operation?: "chat" | "compact" | null;
  /** 本轮用户消息(用于校验与本地消息流对齐) */
  content?: string | null;
  startedAtMs?: number | null;
  bufferedEvents?: number;
  lastEvent?: { event: string; json: string } | null;
  /** 本轮唯一 id:SSE 事件 id 协议为 "turnId:seq",Last-Event-ID 续传按它圈定轮次 */
  turnId?: string | null;
  /** 本轮目前已发出的最大事件序号 */
  lastSeq?: number | null;
}

/** 对话运行条目(M3-01,后端 chat_run;任务页「正在处理」聚合用)。 */
export interface ChatRunInfo {
  id: string;
  sessionId: string;
  sessionTitle: string | null;
  /** queued/running/awaiting_approval/cancelling/completed/partial/failed/cancelled/interrupted */
  status: string;
  content: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

/** GET /chat/runs → 对话运行列表(status 可选逗号分隔过滤)。 */
export async function fetchChatRuns(statuses?: string[], limit = 50): Promise<ChatRunInfo[]> {
  const params = new URLSearchParams();
  if (statuses && statuses.length > 0) params.set("status", statuses.join(","));
  params.set("limit", String(limit));
  return requestJson<ChatRunInfo[]>(`/chat/runs?${params.toString()}`, { cache: "no-store" });
}

/**
 * 订阅进行中轮次的事件流(断线重连/切页返回):
 * 服务端先回放已缓冲事件,再实时推送直至 done/error;无进行中轮次时发 `idle` 后关闭。
 * 每个事件带单调递增的 SSE id(序号):EventSource 传输层断线自动重连时,
 * 浏览器自动携带 Last-Event-ID,服务端只回放该序号之后的事件——重连不重不漏,
 * 不会出现旧实现「全量回放 → 文本重复追加」的问题。
 * 返回清理函数(关闭 EventSource)。
 */
export function attachLiveTurnStream(
  sessionId: string,
  handlers: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (stopped: boolean) => void;
    onError: (error: Error) => void;
    onIdle: () => void;
  }
): () => void {
  // EventSource 无法携带自定义 header；服务端过滤器同样接受查询令牌。
  const es = new EventSource(withAuthToken(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/turn/stream`));
  for (const type of ["snapshot", "step", "delta", "reasoning_delta", "sources", "context_usage", "approval_required", "question_required", "done", "error", "title"]) {
    es.addEventListener(type, event => {
      const data = (event as MessageEvent).data;
      // 无 data 是传输错误，交给 EventSource 按 Last-Event-ID 重连。
      if (!data) return;
      try {
        const decoded = decodeChatStreamEvent(type, data);
        if (!decoded) return;
        handlers.onEvent(decoded);
        if (decoded.type === "finish") {
          invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
          es.close();
          handlers.onDone(!!decoded.stopped);
        }
        // 业务 error 后继续接收 done，保留服务端终态和计量。
      } catch (error) {
        es.close();
        handlers.onError(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }
  es.addEventListener("idle", () => {
    invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
    es.close();
    handlers.onIdle();
  });
  return () => es.close();
}

export function fetchAgentSettings(): Promise<AgentSettings> {
  return requestJson<AgentSettings>("/chat/settings", { cache: "no-store" });
}

export function saveAgentSettings(patch: AgentSettings): Promise<AgentSettings> {
  return requestJson<AgentSettings>("/chat/settings", {
    method: "PUT", body: JSON.stringify(patch),
  });
}
