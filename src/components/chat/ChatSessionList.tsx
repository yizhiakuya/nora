'use client';

import { toast } from "sonner";
import { Plus, MessageSquare, Trash2 } from "lucide-react";
import { useChatSessions } from "@/hooks/useChatSessions";

function relativeTime(ts: number): string {
  const diff = Date.now() - ts;
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} 小时前`;
  return `${Math.floor(h / 24)} 天前`;
}

export function ChatSessionList() {
  const sessions = useChatSessions((s) => s.sessions);
  const activeId = useChatSessions((s) => s.activeId);
  const createSession = useChatSessions((s) => s.createSession);
  const deleteSession = useChatSessions((s) => s.deleteSession);
  const setActive = useChatSessions((s) => s.setActive);

  const sorted = [...sessions].sort((a, b) => b.updatedAt - a.updatedAt);

  const handleDelete = (id: string, title: string) => {
    deleteSession(id);
    toast.success(`会话「${title}」已删除`);
  };

  return (
    <div className="w-60 shrink-0 hidden md:flex flex-col border-r border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900">
      <div className="p-3">
        <button
          type="button"
          onClick={() => createSession()}
          className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white transition-colors cursor-pointer"
        >
          <Plus className="w-3.5 h-3.5" /> 新对话
        </button>
      </div>
      <div className="flex-1 overflow-y-auto custom-scroll px-2 pb-3 space-y-0.5">
        {sorted.map((session) => {
          const active = session.id === activeId;
          return (
            <div
              key={session.id}
              onClick={() => setActive(session.id)}
              className={`group flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors ${active ? "bg-blue-50 dark:bg-blue-950/40" : "hover:bg-gray-50 dark:hover:bg-gray-800"}`}
            >
              <MessageSquare className={`w-3.5 h-3.5 shrink-0 ${active ? "text-blue-600 dark:text-blue-400" : "text-gray-400 dark:text-gray-500"}`} />
              <div className="min-w-0 flex-1">
                <div className={`text-xs font-medium truncate ${active ? "text-blue-700 dark:text-blue-300" : "text-gray-800 dark:text-gray-100"}`}>
                  {session.title}
                </div>
                <div className="text-[10px] text-gray-400 dark:text-gray-500">
                  {session.messages.length > 0 ? `${session.messages.length} 条消息 · ` : ""}{relativeTime(session.updatedAt)}
                </div>
              </div>
              <button
                type="button"
                title="删除会话"
                onClick={(e) => { e.stopPropagation(); handleDelete(session.id, session.title); }}
                className="w-6 h-6 flex items-center justify-center rounded-md text-gray-300 dark:text-gray-600 opacity-0 group-hover:opacity-100 hover:text-red-500 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40 transition-all shrink-0"
              >
                <Trash2 className="w-3 h-3" />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
