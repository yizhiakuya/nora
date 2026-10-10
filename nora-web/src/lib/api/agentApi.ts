import type { ChatMessage, ChatResponder, ChatStep, ChatStepResult, ChatContextUsage, ApprovalRequest, QuestionRequest, TaskContextPayload } from "./chatApi";
import { applyChatStreamEvent, type ChatStreamEvent } from "./chatApi";
import { emitSessionTitle } from "./sessionTitleEvents";
import { API_BASE, ApiError, defaultTimeoutSignal, requestJson } from "./client";
import { authHeaders, handleUnauthorized, withAuthToken } from "@/lib/auth";
import type { AgentSettings, Citation } from "@/types";
import { parseSSEStream } from "./sse";
import { cached, invalidateForPath } from "./requestCache";
import { hmFromLocalIso } from "@/lib/format";
import { estimateTextTokens } from "@/lib/tokenEstimate";

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

interface ReasoningDeltaPayload {
  roundIndex?: number;
  content?: string;
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
  const title = step.title === "调用工具 execute_sql" ? "查询数据库" : step.title === "调用工具 read_service_logs" ? "读取服务日志" : step.title;
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
    input: step.input ? displayInput : undefined,
    inputTokens: step.input?.rawArgs != null ? estimateTextTokens(step.input.rawArgs) : undefined,
    result: step.result,
    context: step.context,
    progress: step.progress,
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
  const response = await fetch(
    `${API_BASE}/chat/approvals/${encodeURIComponent(approvalToken)}?sessionId=${encodeURIComponent(sessionId)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeaders() },
      body: JSON.stringify({ approved }),
    }
  );
  if (response.status === 401) handleUnauthorized();
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `审批请求失败(HTTP ${response.status})`);
  }
  return approved;
}

/** 回答挂起的 ask_user 提问(2026-09-29);答案作为工具结果回填同一轮。 */
export async function answerQuestion(
  sessionId: string,
  questionToken: string,
  answer: string
): Promise<boolean> {
  const response = await fetch(
    `${API_BASE}/chat/answers/${encodeURIComponent(questionToken)}?sessionId=${encodeURIComponent(sessionId)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeaders() },
      body: JSON.stringify({ answer }),
    }
  );
  if (response.status === 401) handleUnauthorized();
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `回答提交失败(HTTP ${response.status})`);
  }
  return true;
}

function createResponder(compactRequestId?: string): ChatResponder {
  return async (message, onUpdate, sessionId, model, _reasoningLevel, _permissionMode, signal, providerId, context) => {
    if (!sessionId) {
      throw new Error("Agent API requires a sessionId");
    }

    let response: Response;
    try {
      response = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/${compactRequestId ? "compact" : "messages"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeaders() },
        body: JSON.stringify(compactRequestId ? { requestId: compactRequestId, focus: message, model, providerId: providerId ?? null } : {
          content: message,
          model,
          providerId: providerId ?? null,
          // M2-01:结构化上下文(可空);后端优先解析它,旧行格式作兼容回退
          context: context ?? null,
        }),
        // 切页时可断开连接；停止执行使用后端取消接口，并继续接收终态。
        signal,
      });
    } catch (error) {
      // 用户主动停止:以正常结果结束(部分文本已由 onUpdate 流出),不按错误处理
      if (signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) {
        return;
      }
      throw new Error("Cannot connect to agent-service", { cause: error });
    }

    if (response.status === 401) {
      handleUnauthorized();
    }
    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `Agent API HTTP ${response.status}`);
    }

    // 写后失效：本轮会写入消息、可能改变会话标题与活跃时间，缓存必须清。
    // 放在请求成功之后（失败/中止不该清缓存——那会让「停止生成」白丢历史缓存）。
    invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);

    let stream: Partial<ChatMessage> = { content: "", sender: compactRequestId ? "compaction" : undefined, steps: [] };
    let receivedDone = false;
    let streamError: ApiError | undefined;
    const publish = (event: ChatStreamEvent) => {
      const partial = applyChatStreamEvent(stream, event);
      stream = { ...stream, ...partial };
      onUpdate(partial);
    };

    await parseSSEStream(response.body.getReader(), ({ event, data }) => {
      // 用户已停止:退出事件处理(SSE 解析循环由 signal 监听终止)
      if (signal?.aborted) return;
      if (event === "approval_required") {
        const approval = parseData<ApprovalRequest>(data);
        if (approval?.approvalToken) {
          publish({ type: "approval_required", approval });
        }
      } else if (event === "question_required") {
        const question = parseData<QuestionRequest>(data);
        if (question?.questionToken) {
          publish({ type: "question_required", question });
        }
      } else if (event === "step") {
        const payload = parseData<StepPayload>(data);
        if (!payload) return;
        publish({ type: "step", step: normalizeStep(payload, stream.steps?.length ?? 0) });
    } else if (event === "delta") {
        const payload = parseData<DeltaPayload>(data);
        if (!payload?.content) return;
        publish({ type: "delta", content: payload.content, roundIndex: payload.roundIndex });
      } else if (event === "reasoning_delta") {
        const payload = parseData<ReasoningDeltaPayload>(data);
        if (!payload?.content) return;
        publish({ type: "reasoning_delta", content: payload.content, roundIndex: payload.roundIndex });
      } else if (event === "sources") {
        const payload = parseData<Citation[]>(data);
        const sources = normalizeSources(payload);
        if (sources) publish({ type: "sources", sources });
      } else if (event === "context_usage") {
        const payload = parseData<ChatContextUsage>(data);
        if (payload) publish({ type: "context_usage", contextUsage: payload });
      } else if (event === "title") {
        // AI 异步起好的标题：广播给会话 store 刷新侧栏（不占用消息流）
        const payload = parseData<TitlePayload>(data);
        if (payload?.sessionId && payload.title) {
          emitSessionTitle(payload.sessionId, payload.title);
        }
      } else if (event === "done") {
        const donePayload = parseData<DonePayload>(data);
        if (!donePayload) throw new Error("Invalid Agent completion event");
        receivedDone = true;
        publish({
          type: "finish", stopped: donePayload?.stopped, failed: !!streamError || donePayload?.status === "failed",
          turnMetrics: donePayload?.durationMs != null
            ? {
                durationMs: donePayload.durationMs,
                usage: donePayload.usage ?? null,
                contextWindow: donePayload.contextWindow ?? null,
                promptTokens: donePayload.promptTokens ?? null,
                ttftMs: donePayload.ttftMs ?? null,
                status: donePayload.status,
                stopReason: donePayload.stopReason,
                hadToolErrors: donePayload.hadToolErrors,
              }
            : undefined,
        });
      } else if (event === "error") {
        const payload = parseData<ErrorPayload>(data);
        publish({ type: "finish", failed: true });
        // 保留原始错误，同时接收紧随其后的 done 终态与计量。
        streamError = new ApiError({
          message: payload?.message || data || "Agent 执行失败",
          code: 0,
          category: payload?.category,
          errorCode: payload?.errorCode,
          hint: payload?.hint,
          retryable: payload?.retryable,
          traceId: payload?.traceId,
        });
      }
    }, signal);

    if (signal?.aborted) return;
    if (streamError) throw streamError;
    if (!receivedDone) throw new Error("Agent stream ended before completion");
    if (!stream.content && !stream.steps?.length) {
      throw new Error("Agent API returned an empty stream");
    }

  };
}

export const AgentAPI = { sendMessage: createResponder(), compact: createResponder };

/** POST /chat/sessions/{id}/cancel → 中断进行中的轮次(上游 LLM 调用一并中止) */
export async function cancelTurnOnBackend(sessionId: string): Promise<boolean> {
  return requestJson<boolean>(`/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, { method: "POST" });
}

/**
 * GET /chat/sessions → 会话摘要列表(最近活跃在前)
 *
 * 走统一缓存：实测这个端点被反复调用（切换会话、后端在线恢复时各 store
 * 一起 re-sync），30s 内复用同一份结果。会话变更（发消息/改名/删除）会
 * 通过写操作失效缓存，所以「写后立刻看得到」不受影响。
 */
export async function fetchSessions(force = false): Promise<
  { id: string; title: string; titleGenerated?: boolean; messageCount: number; createdAt: string; lastActivity?: string; origin?: string | null }[]
> {
  return cached("/chat/sessions", 30_000, force, fetchSessionsUncached);
}

/** 真实请求；缓存包装见 {@link fetchSessions}。 */
async function fetchSessionsUncached(): Promise<
  { id: string; title: string; titleGenerated?: boolean; messageCount: number; createdAt: string; lastActivity?: string; origin?: string | null }[]
> {
  const res = await fetch(`${API_BASE}/chat/sessions`, { headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchSessions failed: ${res.status}`);
  const body = await res.json();
  const list = (body?.data ?? body) as Array<{
    id: string;
    title: string;
    /** 标题是否已由 AI 生成；false/缺失 = 仍是首轮占位标题 */
    titleGenerated?: boolean;
    messageCount: number;
    createdAt: string;
    lastActivity?: string;
    /** user=用户创建;automation=定时任务会话(2026-09-20) */
    origin?: string | null;
  }>;
  return Array.isArray(list) ? list : [];
}

/**
 * GET /chat/sessions/{id}/messages → 完整消息历史(steps 合并后)
 * 走统一缓存：实测单次响应 123KB、被重复调用 27 次（切会话/重连各拉一遍），
 * 是本地最大的重复负载来源。历史只在发消息/截断时变化，那些写操作会失效缓存。
 */
export async function steerTurnOnBackend(sessionId: string, turnId: string, messageId: string, content: string): Promise<void> {
  await requestJson<boolean>(`/chat/sessions/${encodeURIComponent(sessionId)}/steer`, {
    method: "POST", body: JSON.stringify({ turnId, messageId, content }),
  });
  invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
}

export async function fetchSessionMessages(sessionId: string, force = false): Promise<ChatMessage[]> {
  return cached(
    `/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    30_000,
    force,
    () => fetchSessionMessagesUncached(sessionId),
  );
}

async function fetchSessionMessagesUncached(sessionId: string): Promise<ChatMessage[]> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages`, { headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchSessionMessages failed: ${res.status}`);
  const stored = (await res.json()) as Array<{
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
  }>;
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
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE", headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`deleteSession failed: ${res.status}`);
  // 写后失效：会话列表与历史缓存都要清（删掉的会话不该继续出现在缓存里）
  invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
}

/**
 * DELETE /chat/sessions/{id}/messages/{index} → 从第 index 条(含)起截断历史。
 * 「编辑重发」用:回退到某条用户消息改完重发前,先删掉旧分支,
 * 服务端 LLM 上下文才不会残留已被 UI 丢弃的消息。
 */
export async function truncateMessagesFrom(sessionId: string, index: number): Promise<void> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/messages/${index}`, { method: "DELETE", headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`truncateMessagesFrom failed: ${res.status}`);
  // 写后失效：截断改变了历史，缓存必须清（否则「编辑重发」后仍看到旧分支）
  invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
}

/** GET /chat/sessions/{id}/turn/live → 会话是否有进行中轮次及其已缓冲事件 */
export async function fetchLiveTurn(sessionId: string): Promise<LiveTurnInfo> {
  const res = await fetch(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/turn/live`, { headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchLiveTurn failed: ${res.status}`);
  const envelope = await res.json() as { code: number; data: LiveTurnInfo; message: string };
  return envelope.data ?? { running: false };
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
  const res = await fetch(`${API_BASE}/chat/runs?${params.toString()}`, { headers: authHeaders(), signal: defaultTimeoutSignal() });
  if (!res.ok) throw new Error(`fetchChatRuns failed: ${res.status}`);
  const envelope = await res.json() as { code: number; data: ChatRunInfo[]; message: string };
  return envelope.data ?? [];
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
    onStep?: (s: StepPayload) => void;
    onDelta?: (c: string, roundIndex?: number | null) => void;
    onReasoningDelta?: (roundIndex: number | null, c: string) => void;
    onSources?: (c: Citation[]) => void;
    onContextUsage?: (usage: ChatContextUsage) => void;
    onApproval?: (a: ApprovalRequest) => void;
    /** ask_user 澄清提问(2026-09-29);作答经 answerQuestion 提交 */
    onQuestion?: (q: QuestionRequest) => void;
    onDone?: (p?: DonePayload) => void;
    onError?: (msg: string) => void;
    onIdle?: () => void;
    /**
     * 回放缺口(2026-09-20):服务端滚动窗口已淘汰游标之前的中间事件,
     * 本连接回放的事件序列不完整——已收到的增量内容不可信,应清空后
     * 等 done 从权威消息状态恢复。缺失时忽略(旧后端不发送该事件)。
     */
    onGap?: (info: { cursor: number; oldestSeq: number }) => void;
  }
): () => void {
  // EventSource 只支持 GET,SSE 端点恰好是 GET;token 无需鉴权(本地单用户部署)
  // EventSource 无法自定义 header:令牌走 ?token=(服务端 filter 支持两种通道)
  const es = new EventSource(withAuthToken(`${API_BASE}/chat/sessions/${encodeURIComponent(sessionId)}/turn/stream`));
  es.addEventListener("gap", (e) => {
    const p = safeParse<{ cursor: number; oldestSeq: number }>((e as MessageEvent).data);
    if (p) handlers.onGap?.(p);
  });
  es.addEventListener("step", (e) => {
    const p = safeParse<StepPayload>((e as MessageEvent).data);
    if (p) handlers.onStep?.(p);
  });
  es.addEventListener("delta", (e) => {
    const p = safeParse<DeltaPayload>((e as MessageEvent).data);
    if (p?.content) handlers.onDelta?.(p.content, p.roundIndex);
  });
  es.addEventListener("reasoning_delta", (e) => {
    const p = safeParse<{ roundIndex: number | null; content: string }>((e as MessageEvent).data);
    if (p?.content) handlers.onReasoningDelta?.(p.roundIndex ?? null, p.content);
  });
  es.addEventListener("sources", (e) => {
    const p = safeParse<Citation[]>((e as MessageEvent).data);
    if (p) handlers.onSources?.(p);
  });
  es.addEventListener("context_usage", (e) => {
    const p = safeParse<ChatContextUsage>((e as MessageEvent).data);
    if (p) handlers.onContextUsage?.(p);
  });
  es.addEventListener("approval_required", (e) => {
    const p = safeParse<ApprovalRequest>((e as MessageEvent).data);
    if (p) handlers.onApproval?.(p);
  });
  es.addEventListener("question_required", (e) => {
    const p = safeParse<QuestionRequest>((e as MessageEvent).data);
    if (p) handlers.onQuestion?.(p);
  });
  es.addEventListener("done", (e) => {
    // 接续轮已写入历史，先失效运行中的缓存，避免回填时短暂移除当前回答。
    invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
    handlers.onDone?.(safeParse((e as MessageEvent).data));
    es.close();
  });
  es.addEventListener("error", (e) => {
    // 服务端 error 事件(带 data)与传输层错误(EventSource 自动重连)共用 event name:
    // 有 data 是业务错误;无 data 是连接断开,EventSource 会自动重连,不关闭
    const data = (e as MessageEvent).data;
    if (data) {
      const p = safeParse<{ message?: string }>(data);
      invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
      handlers.onError?.(p?.message ?? "未知错误");
      es.close();
    }
  });
  es.addEventListener("idle", () => {
    invalidateForPath(`/chat/sessions/${encodeURIComponent(sessionId)}`);
    handlers.onIdle?.();
    es.close();
  });
  // 接续流(切页返回/断线重连)同样可能收到标题事件:AI 起标题是旁路任务,
  // 可能在轮次 done 之后才回来——那时前端已切回,靠这条事件刷新侧栏
  es.addEventListener("title", (e) => {
    const p = safeParse<TitlePayload>((e as MessageEvent).data);
    if (p?.sessionId && p.title) emitSessionTitle(p.sessionId, p.title);
  });
  return () => es.close();
}

function safeParse<T>(data: string | undefined): T | undefined {
  if (!data) return undefined;
  try {
    return JSON.parse(data) as T;
  } catch {
    return undefined;
  }
}

export function fetchAgentSettings(): Promise<AgentSettings> {
  return requestJson<AgentSettings>("/chat/settings", { cache: "no-store" });
}

/** PUT /chat/settings → 部分更新 agent 全局设置(merge 语义) */
export function saveAgentSettings(patch: AgentSettings): Promise<AgentSettings> {
  return requestJson<AgentSettings>("/chat/settings", {
    method: "PUT", body: JSON.stringify(patch),
  });
}
