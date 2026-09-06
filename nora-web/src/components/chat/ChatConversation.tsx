'use client';

import { MessageSquare as MessageSquareOpen } from "lucide-react";
import { useChat } from "@/hooks/useChat";
import { ChatMessageItem } from "./ChatMessageItem";
import { ChatInputArea } from "./ChatInputArea";
import { ChatMessage, PermissionMode } from "@/lib/api/chatApi";

interface ChatConversationProps {
  sessionId: string;
  initialMessages: ChatMessage[];
}

/**
 * 上下文用量:优先累计服务端真实 usage(done 事件下发,覆盖工具轮);
 * 任一消息缺 usage 时整段退回字符估算(÷4),避免真伪混计。
 */
function estimateContextTokens(messages: ChatMessage[], input: string): number {
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
    return assistantTokens + Math.ceil(userChars / 4) + Math.ceil(input.length / 4);
  }
  return messages.reduce((sum, m) => sum + Math.ceil(m.content.length / 4), 0) + Math.ceil(input.length / 4);
}

/**
 * 单个会话的对话区：以 sessionId 为 React key 挂载，
 * 切换会话时整体重挂载，从会话 store 载入历史并持续持久化。
 */
export function ChatConversation({ sessionId, initialMessages }: ChatConversationProps) {
  const { messages, input, setInput, isSending, sendMessage, scrollRef, reasoningLevel, setReasoningLevel, permissionMode, setPermissionMode } = useChat({
    initialMessages,
    sessionId,
  });

  return (
    <>
      {/* Chat History */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto p-6 custom-scroll">
        <div className="max-w-3xl mx-auto space-y-8 pb-32">
          {messages.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2 pt-20">
              <MessageSquareOpen className="w-8 h-8 opacity-20" />
              <span className="text-xs">开始新的对话，AI 会基于已启用的能力和知识库回答</span>
            </div>
          ) : (
            messages.map((msg) => <ChatMessageItem key={msg.id} msg={msg} />)
          )}
        </div>
      </div>

      <ChatInputArea input={input} setInput={setInput} isSending={isSending} onSend={sendMessage}
        reasoningLevel={reasoningLevel} onReasoningLevelChange={setReasoningLevel}
        permissionMode={permissionMode} onPermissionModeChange={setPermissionMode}
        contextTokens={estimateContextTokens(messages, input)} />
    </>
  );
}
