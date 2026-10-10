import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { browserApi } from "@/lib/services/browserApi";
import type { BrowserState } from "@/types";

interface Props {
  sessionId: string;
  state: BrowserState;
  frame: number;
  enabled: boolean;
  act: (args: Record<string, unknown>) => Promise<BrowserState | undefined>;
  onError: () => void;
  onLoaded: () => void;
}

export function BrowserViewport({ sessionId, state, frame, enabled, act, onError, onLoaded }: Props) {
  const screen = useRef<HTMLDivElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const wheelTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const wheelDelta = useRef({ dx: 0, dy: 0 });
  const [loaded, setLoaded] = useState(false);
  const [text, setText] = useState("");
  const [displayFrame, setDisplayFrame] = useState(frame);
  const requestingFrame = useRef(true);
  useEffect(() => {
    if (!requestingFrame.current) { requestingFrame.current = true; setDisplayFrame(frame); }
  }, [frame]);
  useEffect(() => {
    const element = viewport.current;
    if (!element) return;
    let timer: ReturnType<typeof setTimeout>;
    const observer = new ResizeObserver(([entry]) => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        const { width, height } = entry.contentRect;
        if (width < 100 || height < 100) return;
        const desired = Math.max(360, Math.min(2000, Math.round((height - (enabled ? 42 : 0)) * 1280 / width)));
        if (Math.abs(desired - state.height) > 20) void act({ action: "resize", width: 1280, height: desired });
      }, 200);
    });
    observer.observe(element);
    return () => { observer.disconnect(); clearTimeout(timer); };
  }, [act, enabled, state.height]);
  useEffect(() => () => { if (wheelTimer.current) clearTimeout(wheelTimer.current); }, []);
  const key = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!enabled || event.nativeEvent.isComposing || event.key === "Process") return;
    if (event.key === "Tab") return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      void act({ action: "type", text: event.key });
    } else if (!["Control", "Shift", "Alt", "Meta"].includes(event.key)) {
      const modifiers = [event.ctrlKey || event.metaKey ? "Control" : "", event.altKey ? "Alt" : "", event.shiftKey ? "Shift" : ""].filter(Boolean);
      void act({ action: "key", key: [...modifiers, event.key].join("+") });
    }
  };
  return <div ref={viewport} className="flex-1 min-h-0 overflow-auto bg-zinc-900 flex flex-col">
    <div ref={screen} role="application" aria-label="网页交互画面" tabIndex={enabled ? 0 : -1}
      className={`relative shrink-0 outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${enabled ? "cursor-default" : "cursor-not-allowed"}`}
      onKeyDown={key} onWheel={event => {
        if (!enabled) return;
        wheelDelta.current.dx += event.deltaX; wheelDelta.current.dy += event.deltaY;
        if (wheelTimer.current) clearTimeout(wheelTimer.current);
        wheelTimer.current = setTimeout(() => {
          const delta = wheelDelta.current; wheelDelta.current = { dx: 0, dy: 0 };
          void act({ action: "scroll", ...delta });
        }, 100);
      }}>
      <img src={browserApi.frameUrl(sessionId, displayFrame)} alt="内置浏览器实时网页画面" draggable={false}
        width={state.width} height={state.height} className="block w-full h-auto select-none"
        onLoad={() => { requestingFrame.current = false; setLoaded(true); onLoaded(); }}
        onError={() => { requestingFrame.current = false; onError(); }}
        onClick={event => {
          if (!enabled) return;
          screen.current?.focus();
          const bounds = event.currentTarget.getBoundingClientRect();
          void act({ action: "point", x: (event.clientX - bounds.left) / bounds.width * state.width,
            y: (event.clientY - bounds.top) / bounds.height * state.height });
        }} />
      {!loaded && <div className="absolute inset-0 grid place-items-center text-xs text-white bg-zinc-900">正在读取真实网页画面…</div>}
    </div>
    {enabled && <form className="sticky bottom-0 mt-auto flex gap-2 p-2 bg-card border-t" onSubmit={event => {
      event.preventDefault(); if (!text) return;
      void act({ action: "type", text }); setText(""); screen.current?.focus();
    }}>
      <input aria-label="向网页输入文字" placeholder="先点选网页输入框，再输入文字（支持中文）" value={text} onChange={event => setText(event.target.value)}
        className="min-w-0 flex-1 bg-muted rounded px-2 py-1.5 text-xs text-foreground outline-none focus:ring-1 focus:ring-blue-500" />
      <button type="submit" disabled={!text} className="px-3 text-xs text-blue-600 disabled:opacity-40">输入</button>
      <button type="button" onClick={() => void act({ action: "key", key: "Enter" })} className="px-2 text-xs text-foreground">回车</button>
      <button type="button" onClick={() => void act({ action: "key", key: "Tab" })} className="px-2 text-xs text-foreground">Tab</button>
    </form>}
  </div>;
}
