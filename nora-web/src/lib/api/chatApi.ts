import type { Citation } from "@/types";

/** 结构化工具入参(execute_sql → sql;read_service_logs → service/limit) */
export interface ChatStepInput {
  sql?: string;
  service?: string;
  limit?: number;
}

/** 结构化工具结果:content 是喂给模型的完整(有界)输出 */
export interface ChatStepResult {
  content?: string;
  summary?: string;
  rowCount?: number;
  lineCount?: number;
  truncated?: boolean;
  error?: string;
}

export interface ChatStep {
  id: string;
  type: "think" | "tool";
  title: string;
  detail?: string;
  duration?: string;
  status: "pending" | "running" | "completed" | "failed" | "declined";
  /** 工具名(如 execute_sql),tool 类型步骤才有 */
  toolName?: string;
  /** 解析后的结构化入参 */
  input?: ChatStepInput;
  /** 结构化结果:输出内容、行数、截断标记、失败原因 */
  result?: ChatStepResult;
  /** ReAct 轮次;0 表示 RAG 检索,1+ 表示模型工具轮 */
  roundIndex?: number;
}

/** 一轮对话的服务端计量(done 事件下发) */
export interface ChatTurnMetrics {
  durationMs: number;
  usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number } | null;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: string;
  isTyping?: boolean;
  error?: string;
  steps?: ChatStep[];
  /** RAG 引用来源（回答基于哪些知识库片段） */
  sources?: Citation[];
  /** 本轮执行计量(done 事件带) */
  turnMetrics?: ChatTurnMetrics;
  /** 当前轮等待用户处理的高风险审批 */
  approval?: ApprovalRequest;
  /** 人性化错误提示 + 建议动作(原始串折叠在详情里) */
  errorHint?: string;
  errorKind?: "network" | "provider" | "auth" | "rate-limit" | "timeout" | "approval" | "unknown";
  /** 原始错误串(排障用,UI 折叠展示) */
  errorRaw?: string;
  /** 本轮被用户主动停止(保留已流出的部分内容) */
  stopped?: boolean;
}

export type PermissionMode = "ask" | "assist" | "full";

export const PERMISSION_MODE_META: Record<PermissionMode, { label: string; description: string }> = {
  ask: { label: "请求批准", description: "编辑外部文件和使用互联网时始终询问" },
  assist: { label: "帮我批准", description: "仅对检测到的风险操作请求批准" },
  full: { label: "完全访问权限", description: "不受限制地访问互联网和你的电脑上的任何文件" },
};

export interface ApprovalRequest {
  approvalToken: string;
  stepId: string;
  actionType: string;
  target: string;
  summary: string;
  risk: string;
}

export type ChatResponder = (
  message: string,
  onUpdate: (partial: Partial<ChatMessage>) => void,
  sessionId?: string,
  model?: string,
  reasoningLevel?: string,
  permissionMode?: PermissionMode,
  /** 中断本轮流式响应（停止生成按钮）；responder 实现方持有对应 AbortController */
  signal?: AbortSignal
) => Promise<void>;

/**
 * 停止生成的结果约定：
 * - 用户中断时 responder 以 { stopped: true } 结束（不是 throw），
 *   已流出的部分文本保留并标记 stoppedAt（消息上显示「已停止」角标）。
 * 研究来源：frontendpatterns.dev/stop-generation —— abort 是正常结果不是错误。
 */
export interface StopHandle {
  stopped?: boolean;
}

