/**
 * 聊天智能滚动:
 * - 用户在底部附近(阈值内)→ 新消息自动跟随
 * - 用户上翻历史 → 暂停自动跟随,显示「回到底部」按钮
 * 研究:ChatGPT/Claude 均为此模式;强制拉底会打断用户阅读历史。
 */
import { useCallback, useEffect, useRef, useState } from "react";

/** 距底部多少像素内算「贴底」 */
const STICK_THRESHOLD = 120;

export function useAutoScroll(deps: unknown[]) {
  const scrollRef = useRef<HTMLDivElement>(null);
  /** 用户是否主动上翻离开底部(手动滚回底部或点按钮后恢复) */
  const stickRef = useRef(true);
  const [showJumpButton, setShowJumpButton] = useState(false);
  /** 程序化滚动期间不响应当次 scroll 事件,避免被误判为用户上翻 */
  const programmaticRef = useRef(false);

  const scrollToBottom = useCallback((smooth = false) => {
    const el = scrollRef.current;
    if (!el) return;
    programmaticRef.current = true;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
    stickRef.current = true;
    setShowJumpButton(false);
    // smooth 滚动需要等动画结束再解除屏蔽
    window.setTimeout(() => {
      programmaticRef.current = false;
    }, smooth ? 500 : 0);
  }, []);

  // scroll 监听:判定用户是否离开/回到底部
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onScroll = () => {
      if (programmaticRef.current) return;
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
      const nearBottom = distance < STICK_THRESHOLD;
      stickRef.current = nearBottom;
      setShowJumpButton(!nearBottom);
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  // 内容更新:仅贴底时跟随(流式期间每个 delta 都会触发)
  useEffect(() => {
    if (stickRef.current) {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { scrollRef, showJumpButton, scrollToBottom };
}
