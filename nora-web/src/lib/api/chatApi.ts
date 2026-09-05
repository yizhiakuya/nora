import { delay } from "./delay";
import type { Citation } from "@/types";
import { generateCitations } from "@/lib/services/ragService";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";

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
}

export type ChatResponder = (
  message: string,
  onUpdate: (partial: Partial<ChatMessage>) => void,
  sessionId?: string,
  model?: string,
  reasoningLevel?: string
) => Promise<void>;

interface IntentResponse {
  thinkTitle: string;
  thinkDetail: string;
  toolTitle: string;
  toolDetail: string;
  text: string;
}

function resolveIntent(message: string): IntentResponse {
  const lower = message.toLowerCase();

  if (/架构|系统|设计|规范|手册|部署/.test(lower)) {
    return {
      thinkTitle: "检索知识库架构文档",
      thinkDetail: "扫描已索引文档，召回系统架构设计与部署规范切片",
      toolTitle: "知识库混合检索",
      toolDetail: "知识库 › 系统架构设计_v3.docx & NestJS部署手册.pdf",
      text: "已为你梳理相关架构与部署核心要点：\n\n### 一、系统总体架构\n- **核心网关**：`api-gateway`（端口 3000）负责统一路由分发、认证与限流。\n- **异步任务**：`worker-service`（端口 3001）监听内部作业队列执行耗时后台计算。\n- **数据持久化**：PostgreSQL（5432）存储关系型业务数据，Redis（6379）提供会话与热点缓存。\n\n### 二、部署与启动顺序\n1. 先确保 `postgres` 与 `redis` 容器就绪并通过健康检查；\n2. 再启动 `api-gateway` 与 `worker-service`；\n3. 详细参数可查阅知识库中的《NestJS部署手册》与《系统架构设计》文档切片。",
    };
  }

  if (/redis|缓存|异常|报错|错误|连接|日志|服务/.test(lower)) {
    return {
      thinkTitle: "分析服务状态与日志",
      thinkDetail: "检查当前微服务运行状态与近 10 分钟 ERROR 级别日志",
      toolTitle: "诊断微服务日志流",
      toolDetail: "环境控制台 › api-gateway & redis 日志",
      text: "关于微服务与环境异常排查结果如下：\n\n1. **根本原因**：`api-gateway` 出现 `ECONNREFUSED` 报错，排查系本地 Redis 服务处于离线状态。\n2. **业务影响**：会话缓存无法命中，导致请求直查数据库并产生重试开销。\n3. **恢复方案**：\n   - 可前往「环境控制台」服务卡片中点击「启动」恢复 Redis；\n   - 或在「自动任务」中执行「修复任务：恢复 Redis 服务」自动重连。",
    };
  }

  if (/订单|order|sql|查询|数据|库|统计/.test(lower)) {
    return {
      thinkTitle: "解析 SQL 查询意图",
      thinkDetail: "连接数据源 myapp_dev，分析 orders 表 Schema 并生成聚合统计",
      toolTitle: "执行 SQL 查询",
      toolDetail: "myapp_dev › SELECT status, COUNT(*) FROM orders GROUP BY status",
      text: "根据 orders 表的实时查询结果，订单状态分布如下：\n\n- **paid（已支付）**：5,214 单（占比 62%）\n- **shipped（已发货）**：2,380 单（占比 28%）\n- **pending（待支付）**：826 单（占比 10%）\n\n💡 **分析建议**：pending 占比（10%）高于上周均值（6%），建议检查支付网关回调延迟。可点击下方「保存到知识库」保留此统计结论。",
    };
  }

  return {
    thinkTitle: "多模态意图理解与知识召回",
    thinkDetail: `分析提问「${message.slice(0, 16)}…」，结合工作台知识库与上下文生成`,
    toolTitle: "工作台上下文检索",
    toolDetail: "Top-K 向量语义匹配 + 关键词重排",
    text: `已为你分析关于「${message}」的相关信息：\n\n我结合了当前工作台中的知识库文档、已连接的数据源以及微服务运行状态。你可以：\n\n1. 在输入框上方启停特定的 **AI 能力**（如 SQL 查询、代码执行或服务日志）；\n2. 若回答具有沉淀价值，可点击下方 **「保存到知识库」** 存入文档中心；\n3. 需要对数据库或服务进行操作时，我也可以为你生成对应的查询语句或自动化任务。`,
  };
}

/** Chat 页初始演示对话 */
export const SEED_CONVERSATION: ChatMessage[] = [
  {
    id: "msg-0",
    role: "user",
    content: "帮我查一下 orders 表最近的订单状态分布，看看有没有异常。",
    timestamp: "14:32",
  },
  {
    id: "msg-1",
    role: "assistant",
    content: "一、订单状态分布\n\n- 已支付（paid）5,214 单，占比 62%，环比持平。\n- 已发货（shipped）2,380 单，占比 28%。\n- 待支付（pending）826 单，占比 10%，高于上周的 6%。",
    timestamp: "14:32",
    steps: [
      { id: "s1", type: "think", title: "思考过程", detail: "解析查询意图，定位 orders 表", status: "completed" },
      { id: "s2", type: "tool", title: "执行 SQL 查询", detail: "SELECT status, COUNT(*) FROM orders… 返回 3 行", duration: "2.34s", status: "completed" },
    ],
    sources: [
      { docName: "orders 表结构", source: "database", chunkIndex: 2, score: 0.93, snippet: "…status VARCHAR NOT NULL — 取值 pending/paid/shipped，默认 pending…" },
      { docName: "周会纪要_0520.txt", source: "file", chunkIndex: 5, score: 0.81, snippet: "…[14:15] 协作：Redis 偶发 ECONNREFUSED，怀疑连接池上限过低…" },
    ],
  },
];

/**
 * 流式输出引擎（按小块推送而非逐字符，降低 re-render 频率）。
 */
async function streamText(text: string, onUpdate: (partial: Partial<ChatMessage>) => void) {
  const CHUNK_SIZE = 3;
  for (let i = CHUNK_SIZE; i < text.length; i += CHUNK_SIZE) {
    onUpdate({ content: text.slice(0, i) });
    await delay(30);
  }
  onUpdate({ content: text });
}

/** 主对话（Chat 页）使用的模拟响应 */
export const MockChatAPI: { sendMessage: ChatResponder } = {
  async sendMessage(_message, onUpdate) {
    const intent = resolveIntent(_message);
    const thinkStep: ChatStep = {
      id: "1",
      type: "think",
      title: intent.thinkTitle,
      detail: intent.thinkDetail,
      status: "completed",
    };
    const toolStep = (status: ChatStep["status"]): ChatStep => ({
      id: "2",
      type: "tool",
      title: intent.toolTitle,
      detail: intent.toolDetail,
      duration: "1.1s",
      status,
    });

    await delay(500);
    onUpdate({ steps: [thinkStep] });

    await delay(700);
    onUpdate({ steps: [thinkStep, toolStep("running")] });

    await delay(900);
    onUpdate({ steps: [thinkStep, toolStep("completed")] });

    await delay(300);
    await streamText(intent.text, onUpdate);
    onUpdate({
      sources: generateCitations(_message, useKnowledgeDocs.getState().docs),
      isTyping: false,
    });
  },
};
