'use client';

import { Header } from "@/components/layout/Header";
import { Sparkles, Star, Share2, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useChat } from "@/hooks/useChat";
import { SEED_CONVERSATION } from "@/lib/api/chatApi";
import { ChatMessageItem } from "@/components/chat/ChatMessageItem";
import { ChatInputArea } from "@/components/chat/ChatInputArea";

export default function ChatPage() {
  const { messages, input, setInput, isSending, sendMessage, scrollRef } = useChat({
    initialMessages: SEED_CONVERSATION,
  });

  return (
    <>
      <Header 
        breadcrumbs={[
          { label: "AI 工作台", isCurrent: false }, 
          { label: "对话", isCurrent: false },
          { label: "分析 Q2 销售数据趋势", isCurrent: true }
        ]}
        actions={
          <div className="flex items-center gap-3 text-gray-500 text-sm">
            <div className="px-2 py-1 bg-blue-50 text-blue-600 border border-blue-100 rounded text-xs flex items-center gap-1.5 mr-2">
                <Sparkles className="w-3 h-3" /> GPT-4o
            </div>
            <Button variant="ghost" size="icon" className="h-8 w-8 text-gray-400 hover:text-yellow-400 hover:bg-yellow-50">
              <Star className="w-4 h-4" />
            </Button>
            <Button variant="ghost" size="icon" className="h-8 w-8 text-gray-400 hover:text-gray-700">
              <Share2 className="w-4 h-4" />
            </Button>
            <Button variant="ghost" size="icon" className="h-8 w-8 text-gray-400 hover:text-red-500 hover:bg-red-50">
              <Trash2 className="w-4 h-4" />
            </Button>
          </div>
        }
      />
      
      {/* Chat History */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto p-6 custom-scroll">
          <div className="max-w-3xl mx-auto space-y-8 pb-32">
              {messages.map((msg) => (
                  <ChatMessageItem key={msg.id} msg={msg} />
              ))}
          </div>
      </div>

      <ChatInputArea 
        input={input} 
        setInput={setInput} 
        isSending={isSending} 
        onSend={sendMessage} 
      />
    </>
  );
}
