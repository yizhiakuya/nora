import type { Citation } from "@/types";

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
  content?: string;
  summary?: string;
  rowCount?: number;
  lineCount?: number;
  truncated?: boolean;
  error?: string;
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
  type: "think" | "tool" | "context";
  title: string;
  detail?: string;
  duration?: string;
  status: "pending" | "running" | "completed" | "failed" | "declined" | "unknown" | "partial";
  /** 工具名(如 execute_sql),tool 类型步骤才有 */
  toolName?: string;
  /** 解析后的结构化入参 */
  input?: ChatStepInput;
  /** 结构化结果:输出内容、行数、截断标记、失败原因 */
  result?: ChatStepResult;
  /** 注入上下文元数据(context 类型步骤才有) */
  context?: ChatStepContext;
  /** 批量任务的实时进度(fetch_media 等;随 running 步骤原地刷新) */
  progress?: ChatStepProgress;
  /** ReAct 轮次;0 表示 RAG 检索/上下文注入,1+ 表示模型工具轮 */
  roundIndex?: number;
}

/** 一轮对话的服务端计量(done 事件下发) */
export interface ChatTurnMetrics {
  durationMs: number;
  usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number } | null;
  /** 生效模型上下文窗口(tokens;null = 未配置,前端退回默认 128k) */
  contextWindow?: number | null;
  /** 服务端最后一次请求的 token 估算(CJK 感知;比前端字符估算准) */
  promptTokens?: number | null;
  /** 首 token 延迟 ms(服务端盖章;null = 未收到任何 token) */
  ttftMs?: number | null;
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
  /** 当前轮等待用户作答的澄清提问(ask_user;2026-09-29) */
  question?: QuestionRequest;
  /** 人性化错误提示 + 建议动作(原始串折叠在详情里) */
  errorHint?: string;
  errorKind?: "network" | "provider" | "auth" | "rate-limit" | "timeout" | "approval" | "unknown";
  /** 原始错误串(排障用,UI 折叠展示) */
  errorRaw?: string;
  /** 本轮被用户主动停止(保留已流出的部分内容) */
  stopped?: boolean;
  /** 本轮流式开始时刻(Date.now());仅进行中的消息有,用于 UI 实时计时 */
  startedAtMs?: number;
  /**
   * 发送者(2026-09-20,定时任务=往会话发消息):user=用户输入;
   * automation=定时任务发送——气泡显示「定时任务」徽章。旧数据 undefined 按 user。
   */
  sender?: "user" | "automation" | "assistant";
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
  refs: Array<{ kind: "file" | "doc" | "skill" | "mcp" | "datasource"; id: string; label: string }>;
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

