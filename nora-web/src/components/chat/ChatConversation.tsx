'use client';

import { Fragment } from "react";
import { ArrowDown } from "lucide-react";
import { useChat } from "@/hooks/useChat";
import { useAutoScroll } from "@/hooks/useAutoScroll";
import { ChatMessageItem } from "./ChatMessageItem";
import { ChatInputArea } from "./ChatInputArea";
import { ChatMessage } from "@/lib/api/chatApi";
import { BrowserTaskCard } from "@/components/browser/BrowserTaskCard";
import type { useBrowser } from "@/hooks/useBrowser";
import { useChatCommands } from "@/hooks/useChatCommands";

interface ChatConversationProps {
  sessionId: string;
  /** 初始输入框内容(如从数据源页跳转预填);仅挂载时生效 */
  initialInput?: string;
  /** 初始引用集合(跨页「交给助手」交接:M2-02);仅挂载时生效 */
  initialRefs?: import("@/lib/chatRefs").ChatRef[];
  /** 挂载后自动发送初始输入(首页「开始新需求」;仅一次,发送后 URL 参数即清) */
  autoSendInitial?: boolean;
  browser?: ReturnType<typeof useBrowser>;
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
export function ChatConversation({ sessionId, initialInput, initialRefs, autoSendInitial, browser }: ChatConversationProps) {
  const { messages, input, setInput, isSending, isStopping, isCompacting, contextMeter, queuedMessages, processingQueuedId, insertQueuedMessage, removeQueuedMessage, restoreQueuedMessage, sendMessage, sendTaskMessage, reasoningLevel, setReasoningLevel, permissionMode, setPermissionMode, stopGenerating, retryMessage, editAndResend, refs, addRef, removeRef, limitToRefs, setLimitToRefs } = useChat({
    initialInput,
    initialRefs,
    autoSendInitial,
    sessionId,
  });
  const commands = useChatCommands(input, setInput, sendMessage);
  // 跟随消息内容与流式状态变化;切会话时组件重挂载自动贴底
  const { scrollRef, showJumpButton, scrollToBottom } = useAutoScroll([
    messages,
    messages[messages.length - 1]?.content,
  ]);

  return (
    <>
      {browser?.state?.task && <BrowserTaskCard sessionId={sessionId} browser={browser} isSending={isSending} onMessage={sendTaskMessage} onStop={stopGenerating} />}
      {/* Chat History */}
      <div className="relative flex-1 min-h-0">
        <div ref={scrollRef} className="h-full overflow-y-auto px-4 py-8 custom-scroll [@container(min-width:640px)]:px-6">
          <div className="max-w-[820px] mx-auto space-y-8">
            {messages.length === 0 ? (
              <div className="flex flex-col items-center justify-center text-center pt-[12vh] pb-8">
                <img src="/brand-logo.png" alt="" width={36} height={36} className="mb-5 opacity-80" />
                <h1 className="text-2xl font-medium tracking-tight">从这里，开始一件事。</h1>
                <p className="mt-3 text-[13px] text-muted-foreground leading-relaxed max-w-sm">描述你的目标，Nora 会与你一起推进。<br />输入 @ 添加文件，或 / 选择技能与命令。</p>
              </div>
            ) : (
              messages.map((msg, i) => (
                <Fragment key={msg.id}>
                  {shouldShowTimeDivider(messages[i - 1], msg) && (
                    <div className="flex justify-center pb-1">
                      <span className="text-[11px] text-muted-foreground/70 tabular-nums">
                        {msg.timestamp}
                      </span>
                    </div>
                  )}
                  <ChatMessageItem
                    msg={msg}
                    sessionId={sessionId}
                    onRetry={msg.turnMetrics?.status === "interrupted" && i !== messages.length - 1 ? undefined : retryMessage}
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
            className="absolute bottom-3 right-4 z-10 w-8 h-8 rounded-full bg-card border border-border shadow-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer animate-in fade-in slide-in-from-bottom-2"
            title="回到底部"
          >
            <ArrowDown className="w-4 h-4" />
          </button>
        )}
      </div>

      <ChatInputArea input={input} setInput={setInput} isSending={isSending} isStopping={isStopping} isCompacting={isCompacting} onSend={commands.send} onStop={stopGenerating}
        sessionId={sessionId} commandPanel={commands.panel} onCloseCommandPanel={commands.closePanel}
        settingsMenu={commands.settingsMenu} onSettingsMenuChange={commands.setSettingsMenu}
        queuedMessages={queuedMessages} processingQueuedId={processingQueuedId}
        onInsertQueued={insertQueuedMessage} onRemoveQueued={removeQueuedMessage} onRestoreQueued={restoreQueuedMessage}
        reasoningLevel={reasoningLevel} onReasoningLevelChange={setReasoningLevel}
        permissionMode={permissionMode} onPermissionModeChange={setPermissionMode}
        refs={refs} onAddRef={addRef} onRemoveRef={removeRef}
        limitToRefs={limitToRefs} onLimitToRefsChange={setLimitToRefs}
        contextMeter={contextMeter} />
    </>
  );
}
