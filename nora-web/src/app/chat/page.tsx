'use client';

import { useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { Sparkles, Star, Share2, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useChatSessions } from "@/hooks/useChatSessions";
import { ChatConversation } from "@/components/chat/ChatConversation";
import { useModelProviders } from "@/hooks/useModelProviders";
import { toast } from "sonner";

export default function ChatPage() {
  const navigate = useNavigate();
  const sessions = useChatSessions((s) => s.sessions);
  const activeId = useChatSessions((s) => s.activeId);
  const deleteSession = useChatSessions((s) => s.deleteSession);
  const defaultModel = useModelProviders((s) => s.defaultModel);

  const active = sessions.find((s) => s.id === activeId) ?? sessions[0];

  return (
    <>
      <Header 
        breadcrumbs={[
          { label: "工作台", isCurrent: false }, 
          { label: "对话", isCurrent: false },
          { label: active?.title ?? "新对话", isCurrent: true }
        ]}
        actions={
          <div className="flex items-center gap-3 text-muted-foreground text-sm">
            <button
              type="button"
              onClick={() => navigate("/settings?tab=模型管理")}
              className="px-2 py-1 bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border border-blue-100 dark:border-blue-900 rounded text-xs flex items-center gap-1.5 mr-2 hover:bg-blue-100 dark:hover:bg-blue-900/50 cursor-pointer transition-colors"
              title="点击管理模型服务商"
            >
              <Sparkles className="w-3 h-3" /> {defaultModel}
            </button>
            <Button variant="ghost" size="icon" className="h-8 w-8 text-muted-foreground hover:text-yellow-400 dark:hover:text-yellow-300 hover:bg-yellow-50 dark:hover:bg-yellow-950/40">
              <Star className="w-4 h-4" />
            </Button>
            <Button variant="ghost" size="icon" className="h-8 w-8 text-muted-foreground hover:text-foreground">
              <Share2 className="w-4 h-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8 text-muted-foreground hover:text-red-500 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40"
              title="删除当前会话"
              onClick={() => { if (active) { deleteSession(active.id); toast.success("会话已删除"); } }}
            >
              <Trash2 className="w-4 h-4" />
            </Button>
          </div>
        }
      />
      <div className="flex-1 flex overflow-hidden">
        <div key={active?.id} className="flex-1 relative flex flex-col min-w-0">
          <ChatConversation sessionId={active.id} initialMessages={active.messages} />
        </div>
      </div>
    </>
  );
}

