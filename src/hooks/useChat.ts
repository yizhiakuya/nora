import { useState, useCallback, useRef, useEffect } from "react";
import { ChatMessage, ChatResponder, MockChatAPI } from "@/lib/api/chatApi";

interface UseChatOptions {
  initialMessages?: ChatMessage[];
/** 响应器：决定谁来回应用户消息（主对话 / 调试预览等场景） */
  responder?: ChatResponder;
}

const formatTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

export function useChat({ initialMessages = [], responder = MockChatAPI.sendMessage }: UseChatOptions = {}) {
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
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
      await responder(content, (partial) => updateMessage(assistantMsgId, partial));
    } catch (error) {
      console.error("Failed to send message:", error);
    } finally {
      if (mountedRef.current) setIsSending(false);
    }
  }, [input, isSending, responder, updateMessage]);

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
  };
}
