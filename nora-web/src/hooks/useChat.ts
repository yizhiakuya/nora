import { useState, useCallback, useRef, useEffect } from "react";
import { ChatMessage, type ChatResponder, type PermissionMode } from "@/lib/api/chatApi";
import { AgentAPI } from "@/lib/api/agentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { useChatSessions } from "./useChatSessions";
import { useModelProviders } from "./useModelProviders";

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

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
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
    setIsSending(true);

    try {
      await responder(
        content,
        (partial) => updateMessage(assistantMsgId, partial),
        sessionId,
        model === "未配置" ? undefined : model,
        reasoningLevel,
        permissionMode
      );
    } catch (error) {
      console.error("Failed to send message:", error);
      updateMessage(assistantMsgId, { error: (error as Error).message || "发送失败", isTyping: false });
    } finally {
      if (mountedRef.current) setIsSending(false);
    }
  }, [input, isSending, responder, sessionId, model, reasoningLevel, permissionMode, updateMessage]);

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
  };
}
