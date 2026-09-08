import { useState, useCallback, useRef, useEffect } from "react";
import { ChatMessage, type ChatResponder, type PermissionMode } from "@/lib/api/chatApi";
import { AgentAPI, cancelTurnOnBackend } from "@/lib/api/agentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { useChatSessions } from "./useChatSessions";
import { useModelProviders } from "./useModelProviders";
import { humanizeError } from "@/lib/errorMessages";

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
  /** 对话框选的思考等级;undefined = 跟随设置页该模型默认 */
  const [reasoningLevel, setReasoningLevel] = useState<string | undefined>(undefined);
  /** 权限模式:默认仅对高风险操作请求批准 */
  const [permissionMode, setPermissionMode] = useState<PermissionMode>("assist");
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
  useEffect(() => {
    if (!sessionId) return;
    useChatSessions.getState().saveMessages(sessionId, messages);
  }, [messages, sessionId]);

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
            ? { id: assistantMsgId, role: "assistant", content: "", timestamp: formatTime(), isTyping: true }
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
  };
}
