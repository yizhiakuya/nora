import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ChatMessage } from "@/lib/api/chatApi";
import { AgentAPI, fetchSessions, fetchSessionMessages, deleteSessionOnBackend } from "@/lib/api/agentApi";
import { USE_BACKEND } from "@/lib/api/client";

export interface ChatSession {
  id: string;
  title: string;
  updatedAt: number;
  messageCount?: number;
  /** 本地缓存的消息(仅离线兜底/即时渲染用;真相在后端) */
  messages: ChatMessage[];
}

interface ChatSessionsState {
  sessions: ChatSession[];
  activeId: string;
  /** 后端同步中 */
  syncing: boolean;
  createSession: () => string;
  deleteSession: (id: string) => void;
  /** 撤销删除:后端无法恢复已删会话,这里只恢复本地状态并提示用户 */
  undoDeleteSession: (session: ChatSession, index: number) => void;
  setActive: (id: string) => void;
  /** 发送过程中本地缓存消息(流式渲染用) */
  saveMessages: (id: string, messages: ChatMessage[]) => void;
  /** 从后端拉会话列表(启动/聚焦时调用);失败静默保留本地缓存 */
  syncFromBackend: () => Promise<void>;
  /** 切换会话时从后端拉完整历史,替换本地缓存 */
  loadHistory: (id: string) => Promise<void>;
}

const DEFAULT_TITLE = "新对话";

/**
 * 每会话本地消息写入版本号:loadHistory 返回后比对,落后即丢弃快照。
 * 否则流式/停止前后端晚到的旧历史(如健康检查重连触发的 loadHistory)会
 * 盲目覆盖缓存,把刚结束轮次的本地终态(stopped 标记、部分内容)整个冲掉。
 */
const localWriteVersions = new Map<string, number>();
function bumpLocalWriteVersion(id: string): void {
  localWriteVersions.set(id, (localWriteVersions.get(id) ?? 0) + 1);
}

/**
 * 会话状态:后端 chat_session 表为唯一数据源,localStorage 只作缓存
 * (离线兜底 + 切会话时即时渲染,加载中会被后端历史覆盖)。
 * - syncFromBackend:拉列表(标题/时间排序),本地有而后端无的孤儿会话清除
 * - loadHistory:激活会话时拉完整消息(含 steps/sources,后端已合并去重)
 * - 删除双写:后端删除成功才移除本地;后端失败保留并让下次同步收敛
 */
export const useChatSessions = create<ChatSessionsState>()(
  persist(
    (set, get) => ({
      sessions: [],
      activeId: "",
      syncing: false,
      createSession: () => {
        const id = `sess-${Date.now()}`;
        const session: ChatSession = { id, title: DEFAULT_TITLE, updatedAt: Date.now(), messages: [] };
        set((state) => {
          const withoutSame = state.sessions.filter((s) => s.id !== id);
          return { sessions: [session, ...withoutSame], activeId: id };
        });
        return id;
      },
      deleteSession: (id) => {
        const { sessions, activeId } = get();
        // 记录被删会话与其位置,供 undo 恢复
        const index = sessions.findIndex((s) => s.id === id);
        if (index < 0) return;
        const removed = sessions[index];
        (get() as ChatSessionsState & { _lastDeleted?: { session: ChatSession; index: number } })._lastDeleted =
          { session: removed, index };

        // 乐观移除本地;后端删除延迟到 undo 窗口结束后执行
        const remaining = sessions.filter((s) => s.id !== id);
        set({ sessions: remaining, activeId: activeId === id ? remaining[0]?.id ?? "" : activeId });
      },
      undoDeleteSession: (session, index) => {
        // 恢复本地状态,取消待执行的后端删除
        set((state) => {
          const sessions = [...state.sessions];
          sessions.splice(Math.min(index, sessions.length), 0, session);
          return { sessions, activeId: session.id };
        });
        (get() as ChatSessionsState & { _lastDeleted?: unknown })._lastDeleted = undefined;
      },
      setActive: (id) => {
        set({ activeId: id });
        void get().loadHistory(id);
      },
      saveMessages: (id, messages) => {
        // 同引用短路:useChat 收编 store 消息后写回的是同一数组,
        // 不拦截会 map 出新引用 → 触发收编 effect → 无限循环
        const existing = get().sessions.find((s) => s.id === id);
        if (existing && existing.messages === messages) return;
        bumpLocalWriteVersion(id);
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === id
              ? {
                  ...s,
                  messages,
                  messageCount: Math.max(s.messageCount ?? 0, messages.length),
                  updatedAt: Date.now(),
                  title:
                    s.title === DEFAULT_TITLE && messages[0]?.role === "user"
                      ? messages[0].content.slice(0, 24)
                      : s.title,
                }
              : s
          ),
        }));
      },
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        if (get().syncing) return;
        set({ syncing: true });
        try {
          const remote = await fetchSessions();
          const now = Date.now();
          const remoteSessions: ChatSession[] = remote.map((r) => {
            const local = get().sessions.find((s) => s.id === r.id);
            // 后端真实活跃时间优先(发送中本地 updatedAt 更新,取两者较新)
            const serverTime = r.lastActivity ? new Date(r.lastActivity).getTime() : NaN;
            const localTime = local?.updatedAt ?? NaN;
            const updatedAt = Number.isFinite(serverTime) && Number.isFinite(localTime)
              ? Math.max(serverTime, localTime)
              : Number.isFinite(serverTime) ? serverTime : Number.isFinite(localTime) ? localTime : now;
            return {
              id: r.id,
              title: r.title || DEFAULT_TITLE,
              messageCount: r.messageCount,
              updatedAt,
              // 保留本地消息缓存直到 loadHistory 拉到后端历史
              messages: local?.messages ?? [],
            };
          });
          const activeStillThere = remoteSessions.some((s) => s.id === get().activeId);
          set({
            sessions: remoteSessions,
            activeId: activeStillThere ? get().activeId : remoteSessions[0]?.id ?? "",
          });
          // 默认激活的第一个会话总是刷新历史(后台,不阻塞)。
          // 不能用「缓存非空就跳过」:localStorage 里可能躺着陈旧瞬态
          // (旧审批卡/typing 态),必须让后端真史覆盖,否则死卡永远重现
          const first = remoteSessions[0];
          if (first) {
            void get().loadHistory(first.id);
          }
        } catch {
          // 后端不可达:保留 localStorage 缓存,离线兜底
        } finally {
          set({ syncing: false });
        }
      },
      loadHistory: async (id) => {
        if (!USE_BACKEND || !id) return;
        // 快照新鲜度:请求发起后本会话若有过本地消息写入(流式写回/新回合
        // 终态),返回的快照已落后于本地,整份丢弃——否则晚到的旧历史会把
        // 停止/刚结束轮次的本地终态(stopped、部分内容)整个冲掉
        const localWritesAtRequest = localWriteVersions.get(id) ?? 0;
        try {
          const messages = await fetchSessionMessages(id);
          if ((localWriteVersions.get(id) ?? 0) !== localWritesAtRequest) return;
          set((state) => ({
            sessions: state.sessions.map((s) => (s.id === id ? { ...s, messages } : s)),
          }));
        } catch {
          // 网络失败:沿用本地缓存
        }
      },
    }),
    {
      name: "chat-sessions",
      version: 1,
      // v0 是纯本地会话源;v1 起后端为准,messages 只当缓存
      migrate: (state) => ({ ...(state as ChatSessionsState), sessions: [] }),
    }
  )
);

/** 兼容旧引用:发送完成后后端已落库,无需额外上报 */
export const _sendMessage = AgentAPI.sendMessage;
