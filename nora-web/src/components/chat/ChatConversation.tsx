'use client';

import { MessageSquare as MessageSquareOpen } from "lucide-react";
import { useChat } from "@/hooks/useChat";
import { ChatMessageItem } from "./ChatMessageItem";
import { ChatInputArea } from "./ChatInputArea";
import { ChatMessage } from "@/lib/api/chatApi";

interface ChatConversationProps {
  sessionId: string;
  initialMessages: ChatMessage[];
}

/**
 * 单个会话的对话区：以 sessionId 为 React key 挂载，
 * 切换会话时整体重挂载，从会话 store 载入历史并持续持久化。
 */
export function ChatConversation({ sessionId, initialMessages }: ChatConversationProps) {
  const { messages, input, setInput, isSending, sendMessage, scrollRef } = useChat({
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

      <ChatInputArea input={input} setInput={setInput} isSending={isSending} onSend={sendMessage} />
    </>
  );
}
