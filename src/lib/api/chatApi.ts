import { delay } from "./delay";

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
}

export type ChatResponder = (
  message: string,
  onUpdate: (partial: Partial<ChatMessage>) => void
) => Promise<void>;

const CHAT_RESPONSE_TEXT =
  "根据您提供的数据，以下是分析结果的总结：\n\n- **总销售额** 同比增长 15%\n- **核心产品线** 占据了 60% 的营收\n- 建议下个季度继续加大对核心产品线的资源投入。";

const AGENT_RESPONSE_TEXT =
  "根据查询结果，昨天北京地区的平均客单价为 **128.5 元**，相比上周同期上涨了 4.2%。\n\n建议持续关注高价值订单的转化趋势。";

/** Chat 页初始演示对话 */
export const SEED_CONVERSATION: ChatMessage[] = [
  {
    id: "msg-0",
    role: "user",
    content: "请帮我分析一下我们公司 2024 年第二季度的销售数据趋势，按产品线和地区维度分析，并给出主要结论和建议。",
    timestamp: "14:32",
  },
  {
    id: "msg-1",
    role: "assistant",
    content: "一、产品线销售趋势\n\n- 整体趋势：2024 年 Q2 总销售额为 3.2 亿元，环比增长 18.7%，同比增长 23.4%。",
    timestamp: "14:32",
    steps: [
      { id: "s1", type: "think", title: "思考过程", detail: "分析需求与维度", status: "completed" },
      { id: "s2", type: "tool", title: "执行数据查询", detail: "返回 12,532 条销售记录", duration: "2.34s", status: "completed" },
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
  detail: "分析用户意图与查询参数",
  status: "completed",
};

const TOOL_STEP = (status: ChatStep["status"]): ChatStep => ({
  id: "2",
  type: "tool",
  title: "执行数据查询",
  detail: "调用 PostgreSQL 查询器",
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
    onUpdate({ isTyping: false });
  },
};

/** 调试预览使用的模拟响应 */
export const MockAgentChatAPI: { sendMessage: ChatResponder } = {
  async sendMessage(_message, onUpdate) {
    await delay(1000);
    await streamText(AGENT_RESPONSE_TEXT, onUpdate);
    onUpdate({ isTyping: false });
  },
};
