import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import { toast } from "sonner";
import { reconcileChatHistory, type ChatMessage } from "@/lib/api/chatApi";
import { fetchSessions, fetchSession, fetchSessionMessages, deleteSessionOnBackend } from "@/lib/api/agentApi";
import { ApiError } from "@/lib/api/client";
import { subscribeSessionTitle } from "@/lib/api/sessionTitleEvents";
import type { QueuedChatMessage } from "@/types";
import type { ChatRef } from "@/lib/chatRefs";

interface ChatSession {
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
  /**
   * 会话来源(2026-09-20,定时任务=往会话发消息):
   * automation=定时任务专属会话(侧栏显示「定时」徽章)。旧数据 undefined 按 user。
   */
  origin?: "user" | "automation";
  /**
   * 草稿会话(2026-09-20):本地创建、从未发送过消息的会话。
   * 「新建对话」/首页「开始新需求」遇到已有草稿时复用而不是无限创建;
   * 首条消息发出(saveMessages 收到非空消息)即清除。
   */
  draft?: boolean;
  queuedMessages?: QueuedChatMessage[];
  queuePaused?: boolean;
  composerDraft?: { input: string; refs: ChatRef[]; limitToRefs: boolean; pendingInput?: QueuedChatMessage };
}

interface ChatSessionsState {
  sessions: ChatSession[];
  messagesBySession: Record<string, ChatMessage[]>;
  activeId: string;
  /** 后端同步中 */
  syncing: boolean;
  pendingDeletions: Record<string, { session: ChatSession; index: number; expiresAt: number }>;
  createSession: (activate?: boolean) => string;
  deleteSession: (id: string) => boolean;
  undoDeleteSession: (id: string) => boolean;
  commitDeleteSession: (id: string) => Promise<void>;
  setActive: (id: string) => void;
  loadSession: (id: string) => Promise<void>;
  /** 发送过程中本地缓存消息(流式渲染用) */
  saveMessages: (id: string, messages: ChatMessage[] | ((previous: ChatMessage[]) => ChatMessage[])) => void;
  saveComposerDraft: (id: string, draft: ChatSession["composerDraft"]) => void;
  updateQueuedMessages: (id: string, update: (messages: QueuedChatMessage[]) => QueuedChatMessage[], paused?: boolean) => void;
  /** 从后端拉会话列表(启动/聚焦时调用);失败保留已有列表与内存消息 */
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

function sessionSnapshot({ sessions, activeId, pendingDeletions }: ChatSessionsState) {
  return { sessions: sessions.map(session => ({ ...session, messages: [] })), activeId, pendingDeletions: pendingDeletions ?? {} };
}

const deletionTimers = new Map<string, ReturnType<typeof setTimeout>>();
const sessionLoads = new Map<string, object>();
const committingDeletions = new Set<string>();
let sessionsRevision = 0;

function scheduleDeletion(id: string, expiresAt: number) {
  clearTimeout(deletionTimers.get(id));
  deletionTimers.set(id, setTimeout(() => { void useChatSessions.getState().commitDeleteSession(id); }, Math.max(0, expiresAt - Date.now())));
}

/**
 * 后端持久化完整历史；localStorage 只保存索引、草稿和排队消息。
 * 切会话时先显示页面内存中的消息，再加载后端历史。
 * - syncFromBackend:拉列表(标题/时间排序),本地有而后端无的孤儿会话清除
 * - loadHistory:激活会话时拉完整消息(含 steps/sources,后端已合并去重)
 * - 删除先隐藏并保留 5 秒撤销窗口，提交失败恢复，刷新后继续待删除操作。
 */
export const useChatSessions = create<ChatSessionsState>()(
  persist(
    (set, get) => ({
      sessions: [],
      messagesBySession: {},
      activeId: "",
      syncing: false,
      pendingDeletions: {},
      createSession: (activate = true) => {
        // 复用已有草稿会话(2026-09-20 用户反馈:连点「新建对话」/首页反复
        // 发需求会堆积一串空会话)。草稿 = 本地创建、从未发送过消息的会话
        // (draft 标记,不依赖 messages.length——后端会话未加载历史时也是空数组,
        // 不能用"空数组"判定草稿,否则会把老会话误当草稿复用)。
        // 后端行在首条消息发出时才创建(ChatTurnRunner.ensureSession),
        // 复用草稿不需要任何后端操作。
        const { sessions } = get();
        const draft = sessions.find((s) => s.draft);
        if (draft) {
          if (activate) set({ activeId: draft.id });
          return draft.id;
        }
        const id = `sess-${Date.now()}`;
        const session: ChatSession = {
          id, title: DEFAULT_TITLE, titleGenerated: false, updatedAt: Date.now(), draft: true,
        };
        set((state) => {
          const withoutSame = state.sessions.filter((s) => s.id !== id);
          return { sessions: [session, ...withoutSame], activeId: activate ? id : state.activeId };
        });
        return id;
      },
      deleteSession: (id) => {
        const { sessions, activeId } = get();
        const index = sessions.findIndex((s) => s.id === id);
        if (index < 0 || get().pendingDeletions[id]) return false;
        const expiresAt = Date.now() + 5000;
        sessionLoads.delete(id);
        sessionsRevision++;
        const remaining = sessions.filter((s) => s.id !== id);
        set(state => ({
          sessions: remaining, activeId: activeId === id ? remaining[0]?.id ?? "" : activeId,
          pendingDeletions: { ...state.pendingDeletions, [id]: { session: sessions[index], index, expiresAt } },
        }));
        scheduleDeletion(id, expiresAt);
        return true;
      },
      undoDeleteSession: (id) => {
        const pending = get().pendingDeletions[id];
        if (!pending || pending.expiresAt <= Date.now() || committingDeletions.has(id)) return false;
        clearTimeout(deletionTimers.get(id));
        deletionTimers.delete(id);
        sessionsRevision++;
        set((state) => {
          const sessions = state.sessions.filter(session => session.id !== id);
          sessions.splice(Math.min(pending.index, sessions.length), 0, pending.session);
          const pendingDeletions = { ...state.pendingDeletions };
          delete pendingDeletions[id];
          return { sessions, pendingDeletions };
        });
        return true;
      },
      commitDeleteSession: async (id) => {
        const pending = get().pendingDeletions[id];
        if (!pending || committingDeletions.has(id)) return;
        clearTimeout(deletionTimers.get(id));
        deletionTimers.delete(id);
        committingDeletions.add(id);
        sessionsRevision++;
        try {
          // 草稿可能已因绑定工作区而落库，统一请求幂等 DELETE。
          await deleteSessionOnBackend(id);
          sessionsRevision++;
          set(state => {
            const pendingDeletions = { ...state.pendingDeletions };
            const messagesBySession = { ...state.messagesBySession };
            delete pendingDeletions[id]; delete messagesBySession[id];
            return { pendingDeletions, messagesBySession };
          });
        } catch (error) {
          sessionsRevision++;
          set(state => {
            const sessions = state.sessions.filter(session => session.id !== id);
            sessions.splice(Math.min(pending.index, sessions.length), 0, pending.session);
            const pendingDeletions = { ...state.pendingDeletions };
            delete pendingDeletions[id];
            return { sessions, pendingDeletions };
          });
          toast.error(error instanceof Error ? error.message : String(error));
        } finally {
          committingDeletions.delete(id);
        }
      },
      setActive: (id) => {
        if (get().pendingDeletions[id]) return;
        set({ activeId: id });
      },
      loadSession: async (id) => {
        if (get().pendingDeletions[id]) return;
        const request = {};
        sessionLoads.set(id, request);
        let remote;
        try { remote = await fetchSession(id); }
        catch (error) {
          if (sessionLoads.get(id) !== request || get().pendingDeletions[id]) return;
          sessionLoads.delete(id);
          if (error instanceof ApiError && error.code === 404) {
            sessionsRevision++;
            set(state => {
              const sessions = state.sessions.filter(session => session.id !== id);
              const messagesBySession = { ...state.messagesBySession };
              delete messagesBySession[id];
              return { sessions, messagesBySession, activeId: state.activeId === id ? sessions[0]?.id ?? "" : state.activeId };
            });
          }
          throw error;
        }
        if (sessionLoads.get(id) !== request || get().pendingDeletions[id]) return;
        sessionLoads.delete(id);
        set(state => {
          const local = state.sessions.find(session => session.id === id);
          const session: ChatSession = {
            ...local, id, title: remote.title || DEFAULT_TITLE, titleGenerated: remote.titleGenerated,
            messageCount: remote.messageCount, draft: false,
            origin: remote.origin === "automation" ? "automation" : "user",
            updatedAt: Math.max(local?.updatedAt ?? 0, new Date(remote.lastActivity ?? remote.createdAt).getTime()),
          };
          return { sessions: [session, ...state.sessions.filter(item => item.id !== id)].sort((a, b) => b.updatedAt - a.updatedAt) };
        });
      },
      saveMessages: (id, update) => {
        const state = get();
        const existing = state.sessions.find(s => s.id === id);
        if (!existing) return;
        const previous = state.messagesBySession[id] ?? [];
        const messages = typeof update === "function" ? update(previous) : update;
        if (messages === previous) return;
        const metadataChanged = previous.length !== messages.length
          || previous.at(-1)?.isTyping !== messages.at(-1)?.isTyping;
        set({
          messagesBySession: { ...state.messagesBySession, [id]: messages },
          ...(metadataChanged ? { sessions: state.sessions.map(s => s.id === id ? {
            ...s,
            messageCount: Math.max(s.messageCount ?? 0, messages.length),
            updatedAt: Date.now(),
            draft: messages.length === 0 ? s.draft : undefined,
            title: s.title === DEFAULT_TITLE && messages[0]?.role === "user"
              ? placeholderTitle(messages[0].content) : s.title,
          } : s) } : {}),
        });
      },
      updateQueuedMessages: (id, update, paused) => {
        set((state) => ({
          sessions: state.sessions.map((session) => session.id === id
            ? { ...session, queuedMessages: update(session.queuedMessages ?? []), queuePaused: paused ?? session.queuePaused }
            : session),
        }));
      },
      saveComposerDraft: (id, composerDraft) => {
        set(state => ({ sessions: state.sessions.map(session => session.id === id ? { ...session, composerDraft } : session) }));
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
        if (!id) return;
        const current = get().sessions.find((s) => s.id === id);
        if (!current || current.titleGenerated) return; // 已经是 AI 标题，无需轮询
        for (const delay of [1500, 1500, 1500]) {
          await new Promise((r) => setTimeout(r, delay));
          try {
            // force=true：这里就要轮询到后端的新标题，必须绕过缓存，
            // 否则 30s TTL 会让三次轮询都命中同一份旧结果，永远等不到 AI 标题
            const found = await fetchSession(id);
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
        if (get().syncing) return;
        const requestRevision = sessionsRevision;
        set({ syncing: true });
        try {
          const remote = await fetchSessions(true);
          if (requestRevision !== sessionsRevision) return;
          const now = Date.now();
          const remoteSessions: ChatSession[] = remote.filter(r => !get().pendingDeletions[r.id]).map((r) => {
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
              origin: r.origin === "automation" ? "automation" as const : undefined,
              queuedMessages: local?.queuedMessages,
              queuePaused: local?.queuePaused,
              composerDraft: local?.composerDraft,
            };
          });
          const activeStillThere = remoteSessions.some((s) => s.id === get().activeId);
          // 本地草稿(未发消息的会话)在后端没有行,不能被列表同步抹掉——
          // 否则「新建对话 → 侧栏挂载同步」会把刚建的草稿清掉,用户再点又建新的
          // (无限创建的另一半根因,2026-09-20)。草稿排在列表最前(最新)。
          const drafts = get().sessions.filter((s) => s.draft && !remoteSessions.some(remote => remote.id === s.id));
          set({
            sessions: [...drafts, ...remoteSessions],
            messagesBySession: Object.fromEntries(Object.entries(get().messagesBySession)
              .filter(([id]) => !!get().pendingDeletions[id] || [...drafts, ...remoteSessions].some(session => session.id === id))),
            activeId: activeStillThere || drafts.some((d) => d.id === get().activeId)
              ? get().activeId
              : remoteSessions[0]?.id ?? drafts[0]?.id ?? "",
          });
        } catch {
          // 后端不可达:保留会话列表和页面内存消息
        } finally {
          set({ syncing: false });
        }
      },
      loadHistory: async (id) => {
        if (!id) return;
        const previous = get().messagesBySession[id];
        if (previous?.some(message => message.isTyping)) return;
        try {
          const messages = await fetchSessionMessages(id);
          if (get().messagesBySession[id] !== previous) return;
          get().saveMessages(id, current => reconcileChatHistory(current, messages));
        } catch {
          // 网络失败:沿用页面内存缓存
        }
      },
    }),
    {
      name: "chat-sessions",
      version: 3,
      partialize: sessionSnapshot,
      migrate: (state) => sessionSnapshot(state as ChatSessionsState),
      onRehydrateStorage: () => state => {
        if (!state) return;
        for (const [id, pending] of Object.entries(state.pendingDeletions)) {
          scheduleDeletion(id, pending.expiresAt);
        }
      },
      storage: createJSONStorage(() => ({
        getItem: name => localStorage.getItem(name),
        removeItem: name => localStorage.removeItem(name),
        setItem: (name, value) => {
          try {
            if (localStorage.getItem(name) === value) return;
            localStorage.setItem(name, value);
            toast.dismiss("chat-storage-error");
          } catch (error) {
            if (!(error instanceof DOMException) || !["QuotaExceededError", "SecurityError"].includes(error.name)) throw error;
            toast.error(error.message, {
              id: "chat-storage-error",
              description: "草稿、排队消息和待删除操作仅保留在当前页面，刷新后可能丢失。",
            });
          }
        },
      })),
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
