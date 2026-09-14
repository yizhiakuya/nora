import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ChatMessage } from "@/lib/api/chatApi";
import { AgentAPI, fetchSessions, fetchSessionMessages, deleteSessionOnBackend } from "@/lib/api/agentApi";
import { subscribeSessionTitle } from "@/lib/api/sessionTitleEvents";
import { USE_BACKEND } from "@/lib/api/client";

export interface ChatSession {
  id: string;
  title: string;
  /**
   * 标题是否已由 AI 生成。
   * false/未定义 = 仍是首轮占位标题（用户消息开头若干字），AI 标题还在路上。
   * 前端据此决定轮次结束后要不要轮询兜底拉取（见 awaitGeneratedTitle）。
   */
  titleGenerated?: boolean;
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
  /** 应用 AI 生成的会话标题(SSE title 事件;覆盖占位标题) */
  applyTitle: (id: string, title: string) => void;
  /** 兜底拉取 AI 标题:SSE title 事件可能在轮次结束后才回来(见实现注释) */
  awaitGeneratedTitle: (id: string) => Promise<void>;
}

const DEFAULT_TITLE = "新对话";

/** 占位标题字数:与后端 ChatStoreService.placeholderTitle 保持一致,避免视觉跳变。 */
const PLACEHOLDER_CHARS = 20;

/**
 * 首轮占位标题:用户消息开头若干字 + …
 *
 * 与后端 ChatStoreService.placeholderTitle 同规则(折叠空白、超长截断 + 省略号),
 * 这样前端乐观显示的名字与后端落库的一致,AI 标题到达前不会出现两次跳变。
 * 标题只用于侧栏辨识,长消息不该整条塞进去。
 */
function placeholderTitle(content: string): string {
  const flat = (content ?? "").replace(/\s+/g, " ").trim();
  return flat.length <= PLACEHOLDER_CHARS ? flat : `${flat.slice(0, PLACEHOLDER_CHARS)}…`;
}

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
        const session: ChatSession = {
          id, title: DEFAULT_TITLE, titleGenerated: false, updatedAt: Date.now(), messages: [],
        };
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
                      ? placeholderTitle(messages[0].content)
                      : s.title,
                }
              : s
          ),
        }));
      },
      applyTitle: (id, title) => {
        if (!id || !title) return;
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, title, titleGenerated: true } : s)),
        }));
      },
      /**
       * SSE 的 title 事件可能赶不上：AI 起标题是旁路任务，短轮次常常在轮次
       * done（emitter complete、live 缓冲随 turn 结束）之后才返回——事件无人接收。
       * 因此轮次结束后轮询一次会话列表，把真标题捞回来。
       *
       * 轮询而非单次：标题通常 1-3s 内回来，给 3 次（约 1.5s/3s/4.5s）足够；
       * 仍拿不到就放弃（下次进对话页 syncFromBackend 自然会拉到），不为它设长任务。
       */
      awaitGeneratedTitle: async (id) => {
        if (!USE_BACKEND || !id) return;
        const current = get().sessions.find((s) => s.id === id);
        if (!current || current.titleGenerated) return; // 已经是 AI 标题，无需轮询
        for (const delay of [1500, 1500, 1500]) {
          await new Promise((r) => setTimeout(r, delay));
          try {
            const remote = await fetchSessions();
            const found = remote.find((r) => r.id === id);
            // 后端 titleGenerated=true = AI 标题已落库
            if (found?.title && found.titleGenerated) {
              get().applyTitle(id, found.title);
              return;
            }
          } catch {
            return; // 网络失败：放弃，不阻塞
          }
        }
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
              titleGenerated: r.titleGenerated,
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

/**
 * AI 标题事件接线:SSE 收到 title 事件(见 agentApi)→ 覆盖对应会话标题。
 *
 * 在模块加载时订阅一次(store 是单例,pages 共享同一实例),无需在组件里
 * 反复订阅/退订。后端已落库,这里只是把已经过时的占位标题立刻换成真标题;
 * 即使这条事件漏收(用户当时没在对话页),下次 syncFromBackend 也会拉到。
 */
subscribeSessionTitle((sessionId, title) => {
  useChatSessions.getState().applyTitle(sessionId, title);
});

/** 兼容旧引用:发送完成后后端已落库,无需额外上报 */
export const _sendMessage = AgentAPI.sendMessage;
