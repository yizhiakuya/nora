import type { CommandExecution, Citation, ViewerFile, ViewerFileError } from "@/types";
import { estimateChatMessageTokens, estimateModelOutputTokens } from "@/lib/chatContext";

/** 结构化工具入参(execute_sql → sql;read_service_logs → service/limit) */
export interface ChatStepInput {
  sql?: string;
  service?: string;
  limit?: number;
  /** manage_datasource / manage_service / manage_mcp 的操作对象(数据源名/id、纳管源名/id、MCP 服务器名/id);run_command 时存命令原文 */
  target?: string;
}

/** 结构化工具结果:content 是喂给模型的完整(有界)输出 */
export interface ChatStepResult {
  files?: ViewerFile[];
  focusTarget?: string;
  fileErrors?: ViewerFileError[];
  /** 文本写操作的变更记录(「已编辑 N 个文件」卡片;后端 ChatStepDto.FileChange 同构) */
  fileChanges?: FileChange[];
  execution?: CommandExecution;
  content?: string;
  summary?: string;
  rowCount?: number;
  lineCount?: number;
  truncated?: boolean;
  error?: string;
}

/** 一次文本写操作的变更统计(对齐 Codex 编辑卡片;行级 diff 由服务端计算) */
export interface FileChange {
  /** 查看器引用(workspace:相对路径) */
  target: string;
  name: string;
  kind: "write" | "append" | "edit" | string;
  additions: number;
  deletions: number;
  created?: boolean;
}

/**
 * 批量任务的实时进度(fetch_media 等,随 running 步骤原地刷新)。
 * 后端 ChatStepDto.StepProgress 同构。
 */
export interface ChatStepProgress {
  /** 阶段:listing(获取清单)/ downloading */
  phase?: "listing" | "downloading" | string;
  /** 已完成文件数(含跳过/失败) */
  done?: number;
  /** 总文件数;0=清单阶段未知 */
  total?: number;
  /** 当前文件序号(1-based;并发时=最近开始的那个) */
  currentIndex?: number;
  /** 当前正在下载的文件名 */
  currentFile?: string;
  /** 并行下载中的文件数 */
  active?: number;
  /** 已传输字节(完成文件全量 + 在途文件已读部分) */
  bytesDone?: number;
  /** 清单声明的总字节 */
  bytesTotal?: number;
  /** 当前速率(字节/秒;样本不足时为 null) */
  bytesPerSec?: number | null;
  /** 预计剩余秒数(null=未知) */
  etaSeconds?: number | null;
}

/** 注入文件清单条目(注入上下文步骤,instructions 形态) */
export interface ContextFile {
  path: string;
  /** 实际注入字节数 */
  bytes?: number;
  /** 被长度/预算截断 */
  truncated?: boolean;
  /** 文件缺失(注入占位符) */
  missing?: boolean;
  /** 模型实际读到的注入正文(展开可查看) */
  content?: string;
}

/** 目录条目(注入上下文步骤,catalog 形态;如技能目录) */
export interface ContextEntry {
  name: string;
  description?: string;
  category?: string;
}

/**
 * 注入上下文元数据(dsh 自描述模式):form 声明信息形态,前端按 form 渲染;
 * 未知 form 降级为通用展示(不丢内容)。
 */
export interface ChatStepContext {
  form?: "instructions" | "catalog" | string;
  /** 生产者标识(如 workspace-bootstrap / skill-catalog) */
  kind?: string;
  files?: ContextFile[];
  entries?: ContextEntry[];
  /** 日记清单(只列名不注入正文) */
  dailyNotes?: string[];
}

export interface ChatStep {
  id: string;
  type: "think" | "tool" | "context" | "commentary" | "answer";
  title: string;
  detail?: string;
  duration?: string;
  status: "pending" | "running" | "completed" | "failed" | "declined" | "unknown" | "partial";
  /** 工具名(如 execute_sql),tool 类型步骤才有 */
  toolName?: string;
  /** 解析后的结构化入参 */
  input?: ChatStepInput;
  /** 脱敏原始参数的 token 估算，供上下文计量，不在工具详情中重复展示参数。 */
  inputTokens?: number;
  /** 结构化结果:输出内容、行数、截断标记、失败原因 */
  result?: ChatStepResult;
  /** 注入上下文元数据(context 类型步骤才有) */
  context?: ChatStepContext;
  /** 批量任务的实时进度(fetch_media 等;随 running 步骤原地刷新) */
  progress?: ChatStepProgress;
  /** ReAct 轮次;0 表示 RAG 检索/上下文注入,1+ 表示模型工具轮 */
  roundIndex?: number;
}

/** 正文增量按模型轮次原位追加；直连流与断线接续使用同一顺序。 */
function appendRoundText(steps: ChatStep[], roundIndex: number, content: string): ChatStep[] {
  const id = `s-message-${roundIndex}`;
  const index = steps.findIndex(step => step.id === id);
  if (index >= 0) {
    return steps.map((step, i) => i === index
      ? { ...step, detail: (step.detail ?? "") + content, status: "running" }
      : step);
  }
  return [...steps, { id, type: "commentary", title: "进度说明", detail: content, status: "running", roundIndex }];
}

export type ChatStreamEvent =
  | { type: "step"; step: ChatStep }
  | { type: "delta"; content: string; roundIndex?: number | null }
  | { type: "reasoning_delta"; content: string; roundIndex?: number | null }
  | { type: "approval_required"; approval: ApprovalRequest }
  | { type: "question_required"; question: QuestionRequest }
  | { type: "sources"; sources: Citation[] }
  | { type: "context_usage"; contextUsage: ChatContextUsage }
  | { type: "finish"; stopped?: boolean; failed?: boolean; turnMetrics?: ChatTurnMetrics }
  | { type: "gap" };

/** 普通流与恢复流共用消息合并和一次性交互的生命周期。 */
export function applyChatStreamEvent(message: Partial<ChatMessage>, event: ChatStreamEvent): Partial<ChatMessage> {
  const steps = message.steps ?? [];
  switch (event.type) {
    case "step": {
      const next = steps.map(step => event.step.context?.kind !== "user-input" && step.type === "think" && step.status === "running" && step.id !== event.step.id
        ? { ...step, status: "completed" as const } : step);
      const index = next.findIndex(step => step.id === event.step.id);
      if (index < 0) next.push(event.step);
      else next[index] = event.step;
      return {
        steps: next,
        approval: message.approval?.stepId === event.step.id ? undefined : message.approval,
        question: message.question?.stepId === event.step.id && event.step.status !== "running" ? undefined : message.question,
        ...(event.step.type === "answer" ? { content: event.step.detail ?? "" } : {}),
      };
    }
    case "delta": {
      const next = steps.map(step => step.type === "think" && step.status === "running"
        ? { ...step, status: "completed" as const } : step);
      return event.roundIndex != null
        ? { steps: appendRoundText(next, event.roundIndex, event.content) }
        : { steps: next, content: (message.content ?? "") + event.content };
    }
    case "reasoning_delta": {
      const id = `s-reasoning-${event.roundIndex ?? "final"}`;
      const index = steps.findIndex(step => step.id === id);
      return { steps: index < 0
        ? [...steps, { id, type: "think", title: "推理过程", detail: event.content, status: "running", roundIndex: event.roundIndex ?? undefined }]
        : steps.map((step, i) => i === index ? { ...step, detail: (step.detail ?? "") + event.content, status: "running" } : step) };
    }
    case "approval_required": return { approval: event.approval };
    case "question_required": return { question: event.question };
    case "sources": return { sources: event.sources };
    case "context_usage": {
      const previous = message.contextUsage;
      const reset = event.contextUsage.source !== "upstream" || previous?.roundIndex !== event.contextUsage.roundIndex;
      return { contextUsage: {
        ...event.contextUsage,
        receivedTokens: reset ? estimateChatMessageTokens(message) : previous?.receivedTokens ?? estimateChatMessageTokens(message),
        receivedOutputTokens: reset ? estimateModelOutputTokens(message, event.contextUsage.roundIndex)
          : previous?.receivedOutputTokens ?? estimateModelOutputTokens(message, event.contextUsage.roundIndex),
      } };
    }
    case "gap": return { content: "", steps: [], approval: undefined, question: undefined, contextUsage: undefined };
    case "finish": return {
      isTyping: false, approval: undefined, question: undefined,
      ...(event.stopped ? { stopped: true } : {}),
      ...(event.turnMetrics ? { turnMetrics: event.turnMetrics } : {}),
      steps: steps.map(step => {
        if (step.result?.execution) return step;
        if (step.toolName && (step.status === "pending" || step.status === "running")) {
          const pending = step.status === "pending";
          return { ...step, status: pending ? "declined" : "unknown",
            result: { ...step.result, error: pending ? "未执行：流结束前工具尚未开始调用。"
              : "结果未知：未收到工具终态，请先核实实际状态。" } };
        }
        if (step.status !== "running") return step;
        const keepPartial = !event.failed && (step.type === "think" || step.type === "commentary");
        return { ...step, status: keepPartial ? "completed" : "failed",
          detail: step.detail ?? (keepPartial ? "" : "流中断，未收到该步骤结果") };
      }),
    };
  }
}

/** 一轮对话的服务端计量(done 事件下发) */
export interface ChatTurnMetrics {
  durationMs?: number;
  usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number; cacheReadInputTokens?: number; cacheCreationInputTokens?: number } | null;
  /** 输入整理预算；是否配置了模型窗口见 contextUsage.windowConfigured。 */
  contextWindow?: number | null;
  /** 服务端最后一次请求的完整输入实测；缺失时使用校准估算。 */
  promptTokens?: number | null;
  /** 首 token 延迟 ms(服务端盖章;null = 未收到任何 token) */
  ttftMs?: number | null;
  status?: string;
  stopReason?: string;
  hadToolErrors?: boolean;
}

/** 最近请求的完整输入；上游计数包含缓存，窗口未配置时仅代表整理预算。 */
export interface ChatContextUsage {
  promptTokens: number;
  outputTokens?: number | null;
  estimatedTokens?: number;
  contextWindow: number;
  windowConfigured?: boolean;
  roundIndex: number;
  source?: "estimate" | "upstream";
  modelKey?: string;
  cacheReadInputTokens?: number | null;
  cacheCreationInputTokens?: number | null;
  /** 请求开始时已展示的内容计数，前端用于排除已纳入基线的内容。 */
  receivedTokens?: number;
  receivedOutputTokens?: number;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: string;
  isTyping?: boolean;
  /** 分段消息共用运行状态，已有工作过程在整轮结束后才自动收起。 */
  isRunning?: boolean;
  error?: string;
  steps?: ChatStep[];
  /** RAG 引用来源（回答基于哪些知识库片段） */
  sources?: Citation[];
  /** 本轮执行计量(done 事件带) */
  turnMetrics?: ChatTurnMetrics;
  contextUsage?: ChatContextUsage;
  /** 原轮引用和检索范围，重试或继续时沿用。 */
  taskContext?: TaskContextPayload;
  /** 当前轮等待用户处理的高风险审批 */
  approval?: ApprovalRequest;
  /** 当前轮等待用户作答的澄清提问(ask_user;2026-09-29) */
  question?: QuestionRequest;
  /** 原始错误串，兼容本地缓存的旧消息。 */
  errorRaw?: string;
  /** 本轮被用户主动停止(保留已流出的部分内容) */
  stopped?: boolean;
  /** 本轮流式开始时刻(Date.now());仅进行中的消息有,用于 UI 实时计时 */
  startedAtMs?: number;
  /**
   * 发送者(2026-09-20,定时任务=往会话发消息):user=用户输入;
   * automation=定时任务发送——气泡显示「定时任务」徽章。旧数据 undefined 按 user。
   */
  sender?: "user" | "automation" | "steering" | "assistant" | "compaction";
}

/** 插入边界只分开消息展示；同一工具的后续更新仍留在它开始的位置。 */
export function splitChatMessages(messages: ChatMessage[]): ChatMessage[] {
  const inputs = new Set(messages.flatMap(message => (message.steps ?? [])
    .filter(step => step.context?.kind === "user-input").map(step => step.id)));
  if (inputs.size === 0) return messages;
  const users = new Map(messages.filter(message => message.role === "user").map(message => [message.id, message]));
  return messages.flatMap(message => {
    if (inputs.has(message.id) && message.role === "user") return [];
    const steps = message.steps ?? [];
    if (message.role !== "assistant" || !steps.some(step => step.context?.kind === "user-input")) return [message];
    const parts: ChatMessage[] = [];
    let from = 0;
    let id = message.id;
    let timestamp = message.timestamp;
    for (let index = 0; index <= steps.length; index++) {
      const boundary = steps[index];
      const last = index === steps.length;
      if (!last && boundary.context?.kind !== "user-input") continue;
      const segment = steps.slice(from, index);
      const content = last ? message.content : segment.filter(step => step.type === "answer").at(-1)?.detail ?? "";
      if (last || segment.length > 0 || content) parts.push({
        ...message, id, timestamp, content, steps: segment,
        isTyping: last ? message.isTyping : false,
        isRunning: message.isTyping,
        startedAtMs: last ? message.startedAtMs : undefined,
        error: last ? message.error : undefined,
        errorRaw: last ? message.errorRaw : undefined,
        stopped: last ? message.stopped : undefined,
        turnMetrics: last ? message.turnMetrics : undefined,
        sources: last ? message.sources : undefined,
        approval: segment.some(step => step.id === message.approval?.stepId) ? message.approval : undefined,
        question: segment.some(step => step.id === message.question?.stepId) ? message.question : undefined,
      });
      if (!last) {
        const user = users.get(boundary.id) ?? {
          id: boundary.id, role: "user" as const, sender: "steering" as const,
          content: boundary.detail ?? "", timestamp,
        };
        parts.push(user);
        id = `${message.id}-${boundary.id}`;
        timestamp = user.timestamp;
      }
      from = index + 1;
    }
    return parts;
  });
}

/** 同一组对话回填服务端历史时保留渲染 ID，避免重挂载丢失用户查看状态。 */
export function reconcileChatHistory(current: ChatMessage[], history: ChatMessage[]): ChatMessage[] {
  current = splitChatMessages(current);
  const aligned = current.length === history.length && current.every((message, index) =>
    message.role === history[index].role && (message.role !== "user" || message.content === history[index].content));
  return aligned ? history.map((message, index) => ({ ...message, id: current[index].id })) : history;
}

export type PermissionMode = "ask" | "assist" | "full";

export const PERMISSION_MODE_META: Record<PermissionMode, { label: string; description: string }> = {
  ask: { label: "请求批准", description: "每次工具调用都请求批准" },
  assist: { label: "帮我批准", description: "只读操作自动执行，写入和命令等风险操作请求批准" },
  full: { label: "完全访问权限", description: "自动执行工具；关键高风险操作仍请求批准，范围以运行环境为准" },
};

export interface ApprovalRequest {
  approvalToken: string;
  stepId: string;
  actionType: string;
  target: string;
  summary: string;
  risk: string;
  /** 各工具的参数明细(逐行 key=value;密码类参数后端已排除),可能为空 */
  detail?: string;
}

/**
 * ask_user 澄清提问(2026-09-29):agent 经 ask_user 工具暂停轮次等待
 * 用户作答;answerQuestion 提交后答案作为工具结果回填,同一轮继续执行。
 */
export interface QuestionRequest {
  questionToken: string;
  stepId: string;
  question: string;
  /** 可选项(点选即作答;用户仍可自由输入) */
  options?: string[];
  /** 服务端等待上限(秒);超时后 agent 会收到「未回答」提示 */
  timeoutSeconds?: number;
}

export type ChatResponder = (
  message: string,
  onUpdate: (partial: Partial<ChatMessage>) => void,
  sessionId?: string,
  model?: string,
  reasoningLevel?: string,
  permissionMode?: PermissionMode,
  /** 中断本轮流式响应（停止生成按钮）；responder 实现方持有对应 AbortController */
  signal?: AbortSignal,
  /** 模型服务商(渠道)id;同名模型跨渠道时后端据此精确定位实际请求的渠道 */
  providerId?: number,
  /** 结构化任务上下文(M2-01):引用集合等;旧行格式仍随 content 持久化,此字段供后端精确解析 */
  context?: TaskContextPayload
) => Promise<void>;

/**
 * 结构化任务上下文(M2-01,对齐后端 TaskContext):
 * refs 由前端从待发送引用生成;输出/来源/数据选择供后续场景(M2/M4)使用。
 */
export interface TaskContextPayload {
  version: 1;
  refs: Array<{ kind: "file" | "doc" | "skill" | "mcp" | "datasource" | "viewer"; id: string; label: string }>;
  output?: { kind: "workspace" | "fileFolder"; target: string };
  origin?: { kind: "chat" | "query" | "log" | "file" | "automation"; id?: string };
  dataSelection?: { mode: "fixed" | "relativeTime"; sourceId?: string; range?: "thisWeek" | "previousWeek" | "last7Days"; timezone?: string };
  /** 检索范围(B4,2026-09-27):「限定检索」开启时只从这些库/文档召回;
   *  缺省 = 不限(引用排前,其余资料仍参与检索)。 */
  retrievalScope?: { baseId?: number; docIds?: number[]; sources?: string[] };
}

/**
 * 停止生成的结果约定：
 * - 用户中断时 responder 以 { stopped: true } 结束（不是 throw），
 *   已流出的部分文本保留并标记 stoppedAt（消息上显示「已停止」角标）。
 * 研究来源：frontendpatterns.dev/stop-generation —— abort 是正常结果不是错误。
 */
export interface StopHandle {
  stopped?: boolean;
}

