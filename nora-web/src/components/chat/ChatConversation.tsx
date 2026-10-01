'use client';

import { Fragment } from "react";
import { ArrowDown, MessageSquare as MessageSquareOpen } from "lucide-react";
import { useChat } from "@/hooks/useChat";
import { useAgentSettings } from "@/hooks/useChat";
import { useAutoScroll } from "@/hooks/useAutoScroll";
import { ChatMessageItem } from "./ChatMessageItem";
import { ChatInputArea } from "./ChatInputArea";
import { ChatMessage } from "@/lib/api/chatApi";

interface ChatConversationProps {
  sessionId: string;
  initialMessages: ChatMessage[];
  /** 初始输入框内容(如从数据源页跳转预填);仅挂载时生效 */
  initialInput?: string;
  /** 初始引用集合(跨页「交给助手」交接:M2-02);仅挂载时生效 */
  initialRefs?: import("@/lib/chatRefs").ChatRef[];
  /** 挂载后自动发送初始输入(首页「开始新需求」;仅一次,发送后 URL 参数即清) */
  autoSendInitial?: boolean;
}

/**
 * CJK 感知 token 估算(与后端 ContextBudget.estimateTokens 同口径):
 * 中文≈1 token/字、ASCII≈4 字符/token、其余≈1/2。此前一律 ÷4,中文输入
 * 被低估约 4 倍(2026-09-19 用户反馈「上下文不准」的一环)。
 */
function estimateTokensCjk(text: string): number {
  if (!text) return 0;
  let cjk = 0, ascii = 0, other = 0;
  for (const ch of text) {
    const c = ch.codePointAt(0) ?? 0;
    if (
      (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf) ||
      (c >= 0x3040 && c <= 0x30ff) || (c >= 0xac00 && c <= 0xd7af) ||
      (c >= 0x3000 && c <= 0x303f) || (c >= 0xff00 && c <= 0xffef)
    ) cjk++;
    else if (c < 0x80) ascii++;
    else other++;
  }
  return cjk + Math.floor(other / 2) + Math.floor(ascii / 4) + 4;
}

/**
 * 上下文用量:三级来源——① 最后一条 assistant 轮的服务端 prompt 估算
 * (done.contextWindow/promptTokens,与后端裁剪同一套 CJK 感知口径;落库字段
 * 让刷新后仍可读)加本轮输入;② 逐轮累计服务端真实 usage(全部轮都有时);
 * ③ CJK 感知字符估算兜底(旧数据)。不混计:前一级可用就完全不用后一级。
 */
function estimateContextTokens(messages: ChatMessage[], input: string): number {
  const lastAssistant = [...messages].reverse().find((m) => m.role === "assistant");
  if (lastAssistant?.turnMetrics?.promptTokens != null) {
    return lastAssistant.turnMetrics.promptTokens + estimateTokensCjk(input);
  }
  const allMeasured = messages
    .filter((m) => m.role === "assistant")
    .every((m) => m.turnMetrics?.usage?.totalTokens != null);
  if (allMeasured && messages.some((m) => m.role === "assistant")) {
    const assistantTokens = messages
      .filter((m) => m.role === "assistant")
      .reduce((sum, m) => sum + (m.turnMetrics?.usage?.totalTokens ?? 0), 0);
    const userChars = messages
      .filter((m) => m.role === "user")
      .reduce((sum, m) => sum + m.content.length, 0);
    return assistantTokens + Math.ceil(userChars / 4) + estimateTokensCjk(input);
  }
  return messages.reduce((sum, m) => sum + estimateTokensCjk(m.content), 0) + estimateTokensCjk(input);
}

/** 上下文窗口:服务端 done 下发的生效窗口 > 默认 128k */
function resolveContextLimit(messages: ChatMessage[]): number {
  const lastAssistant = [...messages].reverse().find((m) => m.role === "assistant");
  return lastAssistant?.turnMetrics?.contextWindow ?? 128000;
}

/**
 * 时间分隔线判定:首条消息,或与上一条间隔 ≥5 分钟(负差值 = 跨天)时,
 * 在该消息组前居中显示回复时间。timestamp 是 "HH:mm"(历史持久化数据);
 * 后端若返回 ISO 串则走 Date 解析兜底。
 */
const TIME_GAP_MINUTES = 5;

function toMinutes(ts: string): number | null {
  if (!ts) return null;
  const m = /^(\d{1,2}):(\d{2})$/.exec(ts.trim());
  if (m) return Number(m[1]) * 60 + Number(m[2]);
  const d = new Date(ts);
  return isNaN(d.getTime()) ? null : d.getHours() * 60 + d.getMinutes();
}

function shouldShowTimeDivider(prev: ChatMessage | undefined, cur: ChatMessage): boolean {
  if (!prev) return true;
  const a = toMinutes(prev.timestamp);
  const b = toMinutes(cur.timestamp);
  if (a == null || b == null) return false;
  const diff = b - a;
  return diff >= TIME_GAP_MINUTES || diff < 0;
}

/**
 * 单个会话的对话区：以 sessionId 为 React key 挂载，
 * 切换会话时整体重挂载，从会话 store 载入历史并持续持久化。
 * 智能滚动:用户上翻阅读历史时不强制拉底,显示「回到底部」按钮。
 */
export function ChatConversation({ sessionId, initialMessages, initialInput, initialRefs, autoSendInitial }: ChatConversationProps) {
  const { messages, input, setInput, isSending, sendMessage, reasoningLevel, setReasoningLevel, permissionMode, setPermissionMode, stopGenerating, retryMessage, editAndResend, refs, addRef, removeRef, limitToRefs, setLimitToRefs } = useChat({
    initialMessages,
    initialInput,
    initialRefs,
    autoSendInitial,
    sessionId,
  });
  // 跟随消息内容与流式状态变化;切会话时组件重挂载自动贴底
  const { scrollRef, showJumpButton, scrollToBottom } = useAutoScroll([
    messages,
    messages[messages.length - 1]?.content,
  ]);

  return (
    <>
      {/* Chat History */}
      <div className="relative flex-1 min-h-0">
        <div ref={scrollRef} className="h-full overflow-y-auto p-4 custom-scroll">
          <div className="max-w-3xl mx-auto space-y-8">
            {messages.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2 pt-20">
                <MessageSquareOpen className="w-8 h-8 opacity-20" />
                <span className="text-xs">开始新的对话，AI 会基于已启用的能力和知识库回答</span>
              </div>
            ) : (
              messages.map((msg, i) => (
                <Fragment key={msg.id}>
                  {shouldShowTimeDivider(messages[i - 1], msg) && (
                    <div className="flex justify-center pb-1">
                      <span className="text-[10px] text-muted-foreground bg-muted/70 dark:bg-muted/40 px-2.5 py-0.5 rounded-full tabular-nums">
                        {msg.timestamp}
                      </span>
                    </div>
                  )}
                  <ChatMessageItem
                    msg={msg}
                    sessionId={sessionId}
                    onRetry={retryMessage}
                    canRetry={!isSending}
                    onEdit={editAndResend}
                    canEdit={!isSending}
                  />
                </Fragment>
              ))
            )}
          </div>
        </div>

        {/* 回到底部:用户上翻后出现,点击平滑回底并恢复自动跟随 */}
        {showJumpButton && (
          <button
            type="button"
            onClick={() => scrollToBottom(true)}
            className="absolute bottom-4 left-1/2 -translate-x-1/2 z-10 w-9 h-9 rounded-full bg-card border border-border shadow-lg flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer animate-in fade-in slide-in-from-bottom-2"
            title="回到底部"
          >
            <ArrowDown className="w-4 h-4" />
          </button>
        )}
      </div>

      <ChatInputArea input={input} setInput={setInput} isSending={isSending} onSend={sendMessage} onStop={stopGenerating}
        reasoningLevel={reasoningLevel} onReasoningLevelChange={(l) => { setReasoningLevel(l); useAgentSettings.getState().setReasoningLevelOverride(l); }}
        permissionMode={permissionMode} onPermissionModeChange={setPermissionMode}
        refs={refs} onAddRef={addRef} onRemoveRef={removeRef}
        limitToRefs={limitToRefs} onLimitToRefsChange={setLimitToRefs}
        contextTokens={estimateContextTokens(messages, input)}
        contextLimit={resolveContextLimit(messages)} />
    </>
  );
}
