import { useState, useCallback, useRef, useEffect } from "react";
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ChatMessage, type ChatResponder, type PermissionMode } from "@/lib/api/chatApi";
import { AgentAPI, cancelTurnOnBackend, fetchAgentSettings, saveAgentSettings, truncateMessagesFrom } from "@/lib/api/agentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { useChatSessions } from "./useChatSessions";
import { useModelProviders } from "./useModelProviders";
import { humanizeError } from "@/lib/errorMessages";

/**
 * Agent 全局设置(权限模式 / 默认模型 / 思考等级覆写):后端 app_setting
 * 表(key=agent)为唯一真相,localStorage 只作缓存。跨会话、跨浏览器一致;
 * 后端模式启动时拉取,切换时写后端(乐观更新本地)。
 */
interface AgentSettingsState {
  permissionMode: PermissionMode;
  reasoningLevelOverride?: string | undefined;
  setPermissionMode: (mode: PermissionMode) => void;
  setReasoningLevelOverride: (level: string | undefined) => void;
  syncFromBackend: () => Promise<void>;
}

export const useAgentSettings = create<AgentSettingsState>()(
  persist(
    (set, get) => ({
      permissionMode: "assist",
      reasoningLevelOverride: undefined,
      setPermissionMode: (permissionMode) => {
        set({ permissionMode });
        if (USE_BACKEND) void saveAgentSettings({ permissionMode });
      },
      setReasoningLevelOverride: (reasoningLevelOverride) => {
        set({ reasoningLevelOverride });
        if (USE_BACKEND) void saveAgentSettings({ reasoningLevel: reasoningLevelOverride ?? null });
      },
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const s = await fetchAgentSettings();
          if (s.permissionMode === "ask" || s.permissionMode === "assist" || s.permissionMode === "full") {
            set({ permissionMode: s.permissionMode, reasoningLevelOverride: s.reasoningLevel ?? undefined });
          }
        } catch {
          /* 后端不可达:沿用本地缓存 */
        }
      },
    }),
    { name: "chat-agent-settings" }
  )
);

interface UseChatOptions {
  initialMessages?: ChatMessage[];
/** 响应器：决定谁来回应用户消息（主对话 / 调试预览等场景） */
  responder?: ChatResponder;
  /** 传入时消息自动持久化到会话 store */
  sessionId?: string;
}

const formatTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

export function useChat({ initialMessages = [], responder = AgentAPI.sendMessage, sessionId }: UseChatOptions = {}) {
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
  const model = useModelProviders((s) => s.defaultModel);
  /** 对话框思考等级:全局覆写(持久);undefined = 跟随设置页该模型默认 */
  const [reasoningLevel, setReasoningLevel] = useState<string | undefined>(undefined);
  useEffect(() => {
    // 初始化:全局覆写优先;无覆写时回落设置页该模型默认等级
    const override = useAgentSettings.getState().reasoningLevelOverride;
    if (override) setReasoningLevel(override);
  }, []);
  /** 权限模式 + 其余全局设置:后端设置表为真相 */
  const permissionMode = useAgentSettings((s) => s.permissionMode);
  const setPermissionMode = useAgentSettings((s) => s.setPermissionMode);
  const scrollRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  /** 进行中轮次的 AbortController;null = 空闲。「停止生成」按钮调用 abort() */
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // 卸载时中断进行中的流(切会话/离开页面),避免孤儿流更新
      abortRef.current?.abort();
    };
  }, []);

  const scrollToBottom = () => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // 会话持久化：消息变化即写回 store（未传 sessionId 时不持久化，测试/调试场景不受影响）
  // 瞬态字段(approval/isTyping/startedAtMs)不入缓存:审批卡是一次性交互态,
  // 缓存里的死卡 reload 后会重现(其 token 早已被消费/超时),点了只会 500
  // lastSavedRef 记下写回 store 的确切数组引用:saveMessages 存的就是它,
  // 收编 effect 比对引用即可识别「自己写回的回声」,斩断无限循环
  // (否则 map 出的新数组每次都是新引用 → store 变 → 收编 → 再写回 → 死循环白屏)
  const lastSavedRef = useRef<ChatMessage[] | null>(null);
  useEffect(() => {
    if (!sessionId) return;
    const persistable = messages.map(({ approval, isTyping, startedAtMs, ...rest }) => rest);
    lastSavedRef.current = persistable;
    useChatSessions.getState().saveMessages(sessionId, persistable);
  }, [messages, sessionId]);

  // 历史加载收编:loadHistory 晚于挂载完成时,useState 快照不会跟进 store 更新
  // (刷新/切会话后对话区永远空态的根因)。监听 store 中本会话消息:引用变化
  // 且非本 hook 刚写回的数组(回声)、且当前不在流式中 → 以后端历史为准整表替换。
  // 回声判定必须读 store 现值(getState)而非闭包 sessionMessages:回合收尾的
  // 同一 commit 里,persist effect(先声明)已把终态写入 store 并更新 lastSavedRef,
  // 而闭包 sessionMessages 还是渲染时的中间快照——拿旧引用比对会误判为「外部
  // 更新」,把停止前的快照反灌回来(stopped 丢失、推理步骤卡 running、「已停止」
  // 与 meta 行消失)。Zustand set 同步生效,effect 按声明顺序执行,getState 必能
  // 看到 persist 刚写入的数组。晚到的落后 loadHistory 快照在 store 层已被
  // 新鲜度闸门(useChatSessions)丢弃,不会到这里。
  const sessionMessages = useChatSessions((s) =>
    sessionId ? s.sessions.find((x) => x.id === sessionId)?.messages : undefined
  );
  useEffect(() => {
    if (isSending) return; // 流式进行中不抢本地状态,结束后由写回自然收敛
    if (!sessionMessages) return;
    const current = useChatSessions.getState().sessions.find((x) => x.id === sessionId)?.messages;
    if (!current || current === lastSavedRef.current) return; // 自己写回的回声,忽略
    setMessages(current);
  }, [sessionMessages, isSending, sessionId]);

  const updateMessage = useCallback((id: string, partial: Partial<ChatMessage>) => {
    if (!mountedRef.current) return; // 卸载后忽略流式更新
    setMessages((prev) => prev.map((msg) => (msg.id === id ? { ...msg, ...partial } : msg)));
  }, []);

  /** 核心发送逻辑:复用于 sendMessage 与 regenerate */
  const runTurn = useCallback(
    async (content: string, assistantMsgId: string) => {
      const controller = new AbortController();
      abortRef.current = controller;

      setIsSending(true);
      try {
        await responder(
          content,
          (partial) => updateMessage(assistantMsgId, partial),
          sessionId,
          model === "未配置" ? undefined : model,
          reasoningLevel,
          permissionMode,
          controller.signal
        );
        // responder 正常返回:用户停止时保留部分文本并标记 stopped
        if (controller.signal.aborted && mountedRef.current) {
          updateMessage(assistantMsgId, { isTyping: false, stopped: true });
        }
      } catch (error) {
        if (!mountedRef.current) return;
        // 用户主动 abort 不算错误(部分文本保留)
        if (controller.signal.aborted) {
          updateMessage(assistantMsgId, { isTyping: false, stopped: true });
          return;
        }
        // 错误人性化:原始串进 details 折叠,人话文案 + hint 外显
        const raw = (error as Error)?.message || "发送失败";
        const friendly = humanizeError(raw);
        updateMessage(assistantMsgId, {
          error: friendly.message,
          errorHint: friendly.hint,
          errorKind: friendly.kind,
          errorRaw: friendly.raw,
          isTyping: false,
        });
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
        if (mountedRef.current) setIsSending(false);
      }
    },
    [responder, sessionId, model, reasoningLevel, permissionMode, updateMessage]
  );

  const sendMessage = useCallback(async () => {
    if (!input.trim() || isSending) return;

    const content = input.trim();
    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content,
      timestamp: formatTime(),
    };
    const assistantMsgId = crypto.randomUUID();

    setMessages((prev) => [
      ...prev,
      userMsg,
      {
        id: assistantMsgId,
        role: "assistant",
        content: "",
        timestamp: formatTime(),
        isTyping: true,
        startedAtMs: Date.now(),
      },
    ]);
    setInput("");
    await runTurn(content, assistantMsgId);
  }, [input, isSending, runTurn]);

  /**
   * 重试失败的轮次:用原用户消息发起新一轮,失败的助手消息就地清空复用
   * (研究结论:重试按钮上的原消息不丢,错误像对话的一部分而非系统崩溃)。
   */
  const retryMessage = useCallback(
    async (failedMsgId: string) => {
      if (isSending) return;
      const idx = messages.findIndex((m) => m.id === failedMsgId);
      if (idx <= 0) return;
      const userMsg = messages[idx - 1];
      if (userMsg?.role !== "user") return;

      const content = userMsg.content;
      const assistantMsgId = crypto.randomUUID();
      // 失败轮就地替换为新助手消息(保留占位),原 user 消息不动
      setMessages((prev) =>
        prev.map((m) =>
          m.id === failedMsgId
            ? { id: assistantMsgId, role: "assistant", content: "", timestamp: formatTime(), isTyping: true, startedAtMs: Date.now() }
            : m
        )
      );
      await runTurn(content, assistantMsgId);
    },
    [messages, isSending, runTurn]
  );

  /** 停止生成:前端断流 + 通知后端取消轮次(上游 LLM 调用一并中止,不白烧 token) */
  const stopGenerating = useCallback(() => {
    abortRef.current?.abort();
    if (USE_BACKEND && sessionId) {
      void cancelTurnOnBackend(sessionId);
    }
  }, [sessionId]);

  /**
   * 编辑重发:回退到某条用户消息,内容替换为 edited 后重新发送。
   * 上下文一致性:先从本地删除该条及其后全部消息,再调后端截断接口
   * 删服务端同分支历史,然后以新内容正常发送——UI 与 LLM 上下文严格一致。
   * 后端截断失败不阻断(本地已删,下轮同步会收敛;旧历史只是多留一轮)。
   */
  const editAndResend = useCallback(
    async (userMsgId: string, edited: string) => {
      if (isSending || !edited.trim()) return;
      const idx = messages.findIndex((m) => m.id === userMsgId);
      if (idx < 0 || messages[idx].role !== "user") return;

      const kept = messages.slice(0, idx);
      setMessages(kept);
      if (USE_BACKEND && sessionId) {
        try {
          await truncateMessagesFrom(sessionId, idx);
        } catch {
          /* 截断失败:以本地为准继续,后端旧分支由下次写回/同步覆盖语义兜底 */
        }
      }

      const assistantMsgId = crypto.randomUUID();
      setMessages([
        ...kept,
        { id: crypto.randomUUID(), role: "user", content: edited.trim(), timestamp: formatTime() },
        { id: assistantMsgId, role: "assistant", content: "", timestamp: formatTime(), isTyping: true, startedAtMs: Date.now() },
      ]);
      await runTurn(edited.trim(), assistantMsgId);
    },
    [messages, isSending, sessionId, runTurn]
  );

  const clear = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    input,
    setInput,
    isSending,
    sendMessage,
    scrollRef,
    clear,
    reasoningLevel,
    setReasoningLevel,
    permissionMode,
    setPermissionMode,
    stopGenerating,
    retryMessage,
    editAndResend,
  };
}
