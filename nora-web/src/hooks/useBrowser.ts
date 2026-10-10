import { useCallback, useEffect, useRef, useState } from "react";
import { browserApi } from "@/lib/services/browserApi";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";
import { useFileViewer } from "@/hooks/useFileViewer";
import type { BrowserState } from "@/types";
import { humanizeError, type FriendlyError } from "@/lib/errorMessages";

export function useBrowser(sessionId: string) {
  const [state, setState] = useState<BrowserState | null>(null);
  const open = useSideWorkspace(store => store.isOpen && store.panel === "browser");
  const expanded = useSideWorkspace(store => store.expanded);
  const setExpanded = useSideWorkspace(store => store.setExpanded);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<FriendlyError | null>(null);
  const errorSource = useRef<"poll" | "control" | "frame" | null>(null);
  const [frame, setFrame] = useState(0);
  const busyRef = useRef(false);
  const queue = useRef(Promise.resolve());
  const pending = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const receiveState = useCallback((next: BrowserState) => {
    setState(current => ({ ...next, exports: [...next.exports,
      ...(current?.exports ?? []).filter(receipt => !next.exports.some(item => item.id === receipt.id))].slice(0, 100) }));
  }, []);
  useEffect(() => { setState(null); }, [sessionId]);

  const show = useCallback(() => {
    useFileViewer.getState().openBrowser();
  }, []);

  useEffect(() => {
    if (!sessionId) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let historyLoaded = false;
    const poll = async () => {
      if (!busyRef.current && !document.hidden) {
        try {
          const next = await browserApi.state(sessionId, abort.signal, open && !historyLoaded);
          if (abort.signal.aborted) return;
          receiveState(next); historyLoaded = true;
          if (errorSource.current === "poll") { setError(null); errorSource.current = null; }
          if (next.active && open) setFrame(Date.now());
        } catch (failure) {
          if (!abort.signal.aborted) { errorSource.current = "poll"; setError(humanizeError(failure)); }
        }
      }
      if (!abort.signal.aborted) timer = setTimeout(() => void poll(), open ? 1500 : 2500);
    };
    void poll();
    return () => { abort.abort(); clearTimeout(timer); };
  }, [sessionId, open, receiveState]);

  const act = useCallback((args: Record<string, unknown>) => {
    let received: BrowserState | undefined;
    pending.current++; busyRef.current = true; setBusy(true); setError(null); errorSource.current = "control";
    queue.current = queue.current.then(async () => {
      try {
        if (!mounted.current) return;
        const next = await browserApi.control(sessionId, args);
        received = next;
        if (mounted.current) { receiveState(next); if (next.active) setFrame(Date.now()); }
      } catch (failure) {
        if (mounted.current) setError(humanizeError(failure));
      } finally {
        pending.current--; busyRef.current = pending.current > 0;
        if (mounted.current) setBusy(busyRef.current);
      }
    });
    return queue.current.then(() => received);
  }, [sessionId, receiveState]);

  const openLink = useCallback((url: string) => {
    useFileViewer.getState().openBrowser(async () => {
      if (state?.owner !== "human" && !(await act({ action: "takeover" }))) return;
      await act({ action: "open", url, newTab: true });
    });
  }, [act, state?.owner]);

  const verify = useCallback(async (id: string) => {
    setError(null); errorSource.current = "control";
    try {
      await browserApi.verify(sessionId, id);
      const next = await browserApi.state(sessionId, undefined, true);
      if (mounted.current) receiveState(next);
    } catch (failure) {
      if (mounted.current) setError(humanizeError(failure));
    }
  }, [sessionId, receiveState]);

  const refresh = useCallback(async () => {
    setError(null); errorSource.current = "poll";
    try {
      const next = await browserApi.state(sessionId, undefined, open);
      if (mounted.current) { receiveState(next); setFrame(Date.now()); }
    } catch (failure) { if (mounted.current) setError(humanizeError(failure)); }
  }, [sessionId, open, receiveState]);

  return { state, open, show, openLink, hide: () => useFileViewer.getState().close(), expanded, setExpanded, busy, error, frame, act, verify,
    refresh,
    frameLoaded: () => { if (errorSource.current === "frame") { setError(null); errorSource.current = null; } },
    frameError: () => {
      errorSource.current = "frame";
      setError({ message: "网页画面加载失败", hint: "刷新画面后重试", raw: "浏览器画面请求失败", kind: "network" });
    } };
}
