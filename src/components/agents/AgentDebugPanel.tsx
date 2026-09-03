'use client';

import { RefreshCw, MessageSquare, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useChat } from "@/hooks/useChat";
import { MockAgentChatAPI } from "@/lib/api/chatApi";
import { Markdown } from "@/components/shared/Markdown";

export function AgentDebugPanel() {
  const { messages, input, setInput, isSending, sendMessage, scrollRef, clear } = useChat({
    responder: MockAgentChatAPI.sendMessage,
  });

  return (
    <div className="w-[45%] bg-[#f8fafc] bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:20px_20px] flex flex-col relative h-full">
      <div className="absolute top-4 right-4 z-10 flex gap-2">
        <Button variant="outline" size="sm" className="h-7 text-[10px] bg-white/80 backdrop-blur shadow-sm hover:bg-gray-100" onClick={clear}>
          <RefreshCw className="w-3 h-3 mr-1" /> 清空调试记录
        </Button>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-6 pt-16 custom-scroll flex flex-col justify-end">
        {messages.length === 0 ? (
          <div className="text-center pb-20 animate-in fade-in duration-500">
            <div className="w-16 h-16 bg-white border border-gray-200 rounded-2xl mx-auto flex items-center justify-center mb-4 shadow-sm text-gray-400">
              <MessageSquare className="w-6 h-6" />
            </div>
            <h3 className="text-sm font-bold text-gray-700 mb-1">调试预览 (Debug Preview)</h3>
            <p className="text-xs text-gray-500 max-w-xs mx-auto">修改左侧的人设或技能后，在这里发送消息，测试 Agent 的真实反应。</p>
          </div>
        ) : (
          <div className="space-y-4 max-w-full">
            {messages.map((msg) => (
              <div key={msg.id} className={"flex " + (msg.role === "user" ? "justify-end" : "justify-start")}>
                <div className={"p-3 rounded-2xl max-w-[85%] text-sm shadow-sm " + (msg.role === "user" ? "bg-blue-600 text-white rounded-tr-sm" : "bg-white border border-gray-100 text-gray-800 rounded-tl-sm")}>
                  {msg.role === "user" ? (
                    msg.content
                  ) : msg.content ? (
                    <div className="prose prose-sm prose-blue max-w-none [&>*]:my-0">
                      <Markdown>{msg.content}</Markdown>
                      {msg.isTyping && <span className="inline-block w-1.5 h-3.5 ml-1 align-middle bg-blue-500 animate-pulse"></span>}
                    </div>
                  ) : (
                    <div className="flex items-center gap-1 py-0.5">
                      <div className="w-2 h-2 bg-gray-300 rounded-full animate-bounce"></div>
                      <div className="w-2 h-2 bg-gray-300 rounded-full animate-bounce" style={{ animationDelay: "0.2s" }}></div>
                      <div className="w-2 h-2 bg-gray-300 rounded-full animate-bounce" style={{ animationDelay: "0.4s" }}></div>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Debug Input */}
      <div className="p-4 bg-white border-t border-gray-200">
        <div className="relative">
          <textarea
            rows={1}
            placeholder="测试: 帮我查一下昨天北京地区的客单价..."
            className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 pr-10 text-xs focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-500 resize-none transition-all"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
              }
            }}
          ></textarea>
          <Button
            size="icon"
            className="absolute bottom-2 right-2 w-7 h-7 bg-blue-600 hover:bg-blue-700 disabled:opacity-50"
            onClick={sendMessage}
            disabled={!input.trim() || isSending}
          >
            <Send className="w-3.5 h-3.5" />
          </Button>
        </div>
      </div>
    </div>
  );
}
