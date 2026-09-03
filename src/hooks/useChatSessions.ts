import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ChatMessage } from "@/lib/api/chatApi";
import { SEED_CONVERSATION } from "@/lib/api/chatApi";

export interface ChatSession {
  id: string;
  title: string;
  updatedAt: number;
  messages: ChatMessage[];
}

interface ChatSessionsState {
  sessions: ChatSession[];
  activeId: string;
  createSession: () => string;
  deleteSession: (id: string) => void;
  setActive: (id: string) => void;
  /** 保存消息并自动用首条用户消息生成标题 */
  saveMessages: (id: string, messages: ChatMessage[]) => void;
}

const DEFAULT_TITLE = "新对话";

const seedSession = (): ChatSession => ({
  id: "seed-session",
  title: "查询订单状态分布",
  updatedAt: Date.now(),
  messages: SEED_CONVERSATION,
});

/**
 * 对话会话唯一数据源：会话列表、切换、删除、消息持久化
 * （localStorage persist，刷新不丢）。
 */
export const useChatSessions = create<ChatSessionsState>()(
  persist(
    (set, get) => ({
      sessions: [seedSession()],
      activeId: "seed-session",
      createSession: () => {
        const id = `sess-${Date.now()}`;
        const session: ChatSession = { id, title: DEFAULT_TITLE, updatedAt: Date.now(), messages: [] };
        set((state) => ({ sessions: [session, ...state.sessions], activeId: id }));
        return id;
      },
      deleteSession: (id) => {
        const { sessions, activeId } = get();
        const remaining = sessions.filter((s) => s.id !== id);
        const next = remaining.length > 0 ? remaining : [seedSession()];
        set({
          sessions: next,
          activeId: activeId === id ? next[0].id : activeId,
        });
      },
      setActive: (id) => set({ activeId: id }),
      saveMessages: (id, messages) =>
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === id
              ? {
                  ...s,
                  messages,
                  updatedAt: Date.now(),
                  title:
                    s.title === DEFAULT_TITLE && messages[0]?.role === "user"
                      ? messages[0].content.slice(0, 24)
                      : s.title,
                }
              : s
          ),
        })),
    }),
    { name: "chat-sessions" }
  )
);
