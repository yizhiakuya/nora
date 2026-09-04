import { delay } from "./delay";
import type { Citation } from "@/types";
import { generateCitations } from "@/lib/services/ragService";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";

export interface ChatStep {
  id: string;
  type: "think" | "tool";
  title: string;
  detail?: string;
  duration?: string;
  status: "pending" | "running" | "completed" | "failed";
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: string;
  isTyping?: boolean;
  steps?: ChatStep[];
  /** RAG 引用来源（回答基于哪些知识库片段） */
  sources?: Citation[];
}

export type ChatResponder = (
  message: string,
  onUpdate: (partial: Partial<ChatMessage>) => void
) => Promise<void>;

const CHAT_RESPONSE_TEXT =
  "根据 orders 表的查询结果，订单状态分布如下：\n\n- **paid** 5,214 单（62%）\n- **shipped** 2,380 单（28%）\n- **pending** 826 单（10%）\n\npending 占比 10% 略高于上周（6%），建议检查支付回调是否有延迟。";

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

const THINK_STEP: ChatStep = {
  id: "1",
  type: "think",
  title: "思考过程",
  detail: "解析查询意图，定位 orders 表",
  status: "completed",
};

const TOOL_STEP = (status: ChatStep["status"]): ChatStep => ({
  id: "2",
  type: "tool",
  title: "执行 SQL 查询",
  detail: "myapp_dev › orders",
  duration: "1.2s",
  status,
});

/** 主对话（Chat 页）使用的模拟响应 */
export const MockChatAPI: { sendMessage: ChatResponder } = {
  async sendMessage(_message, onUpdate) {
    await delay(600);
    onUpdate({ steps: [THINK_STEP] });

    await delay(800);
    onUpdate({ steps: [THINK_STEP, TOOL_STEP("running")] });

    await delay(1200);
    onUpdate({ steps: [THINK_STEP, TOOL_STEP("completed")] });

    await delay(400);
    await streamText(CHAT_RESPONSE_TEXT, onUpdate);
    onUpdate({
      sources: generateCitations(_message, useKnowledgeDocs.getState().docs),
      isTyping: false,
    });
  },
};



