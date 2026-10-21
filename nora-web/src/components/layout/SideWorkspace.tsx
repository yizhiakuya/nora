import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import { useFileViewer } from "@/hooks/useFileViewer";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";
import { useCommandExecutionPolling } from "@/hooks/useCommandExecutions";
import { useBrowser } from "@/hooks/useBrowser";
import { WorkspaceContext } from "./WorkspaceContext";
import type { WorkspaceTab } from "@/types";
import { viewerTargetFromUrl } from "@/lib/services/viewerApi";

const WorkspacePanel = lazy(() => import("./WorkspacePanel"));

export function SideWorkspace({ children, sessionId }: { children: ReactNode; sessionId: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const browser = useBrowser(sessionId);
  useCommandExecutionPolling(sessionId);
  const tasksOpened = useSideWorkspace(state => state.tasksOpened);
  const files = useFileViewer(state => state.tabs);
  const file = useFileViewer(state => state.active);
  const panel = useSideWorkspace(state => state.panel);
  const browserOpened = useSideWorkspace(state => state.browserOpened);
  const missing = useFileViewer(state => state.missing);
  const open = useSideWorkspace(state => state.isOpen);
  // 原页面隐藏及面板懒加载之前记录入口，避免首次打开时焦点已经落到 body。
  const [focus, setFocus] = useState(() => ({ open, opener: document.activeElement }));
  if (focus.open !== open) {
    setFocus({ open, opener: open ? document.activeElement : focus.opener });
  }
  const wide = useSideWorkspace(state => state.wide);
  const expanded = useSideWorkspace(state => state.expanded);
  const width = useSideWorkspace(state => state.width);
  const [params, setParams] = useSearchParams();
  const target = params.get("viewer");
  const location = useLocation();
  const lastTarget = useRef("");
  const previouslyOpen = useRef(open);
  const targetWasOpen = useRef(false);
  useEffect(() => {
    if (browser.state?.active) useSideWorkspace.setState({ browserOpened: true });
  }, [browser.state?.active]);
  const tabs: WorkspaceTab[] = [
    ...(browser.state?.active ? browser.state.tabs.map(tab => ({ id: `browser:${tab.id}`, kind: "browser" as const, title: tab.title || "新网页", target: tab.id,
      disabled: browser.busy || (browser.state?.owner !== "human" && tab.id !== browser.state?.activeTabId) }))
      : browserOpened ? [{ id: "browser:", kind: "browser" as const, title: "新网页", target: "" }] : []),
    ...(tasksOpened ? [{ id: "tasks", kind: "tasks" as const, title: "后台任务", target: "tasks" }] : []),
    ...files.map(file => ({ id: file.target, kind: "file" as const, title: file.name, target: file.target, missing: missing.has(file.target) })),
  ];
  const activeId = panel === "tasks" ? "tasks" : panel === "browser" ? `browser:${browser.state?.activeTabId ?? ""}` : file?.target ?? "file:loading";
  const selectTab = (id: string) => {
    const tab = tabs.find(tab => tab.id === id);
    if (!tab || tab.disabled) return;
    if (tab.kind === "file") useFileViewer.getState().select(tab.target);
    else if (tab.kind === "tasks") useFileViewer.getState().requestAction(() => useSideWorkspace.getState().show("tasks"));
    else useFileViewer.getState().openBrowser(() => {
      if (tab.target && tab.target !== browser.state?.activeTabId) void browser.act({ action: "select_tab", tabId: tab.target });
    });
  };
  const closeTab = (id: string) => {
    const tab = tabs.find(tab => tab.id === id);
    if (!tab) return;
    if (tab.kind === "file") { useFileViewer.getState().closeTab(tab.target); return; }
    if (tab.kind === "tasks") {
      useSideWorkspace.setState({ tasksOpened: false, focusedCommand: null });
      if (panel === "tasks") {
        if (file) useFileViewer.getState().select(file.target);
        else if (browserOpened) useFileViewer.getState().openBrowser();
        else useFileViewer.getState().close();
      }
      return;
    }
    const finish = () => {
      useSideWorkspace.setState({ browserOpened: false });
      const viewer = useFileViewer.getState();
      if (useSideWorkspace.getState().panel !== "browser") return;
      const remaining = viewer.active ?? viewer.tabs.at(-1);
      if (remaining) viewer.select(remaining.target);
      else if (useSideWorkspace.getState().tasksOpened) useSideWorkspace.getState().show("tasks");
      else viewer.close();
    };
    if (!browser.state?.active) { finish(); return; }
    if (browser.busy || browser.state.owner !== "human") return;
    void browser.act(browser.state.tabs.length === 1 ? { action: "close" } : { action: "close_tab", tabId: tab.target })
      .then(next => { if (next && !next.active) finish(); });
  };
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(entries => useSideWorkspace.setState({ wide: entries[0].contentRect.width >= 840 }));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!target) { lastTarget.current = ""; return; }
    const key = `${location.pathname}:${target}`;
    if (lastTarget.current === key) return;
    lastTarget.current = key;
    void useFileViewer.getState().openTargets([target], target, { sessionId: sessionId || undefined });
  }, [target, location.pathname, sessionId]);
  useEffect(() => {
    const targetStillOpen = !!target && files.some(file => file.target === (viewerTargetFromUrl(target) ?? target));
    if (targetStillOpen) targetWasOpen.current = true;
    if (target && ((previouslyOpen.current && !open) || (targetWasOpen.current && !targetStillOpen))) {
      setParams(previous => { const next = new URLSearchParams(previous); next.delete("viewer"); return next; }, { replace: true });
    }
    if (!target) targetWasOpen.current = false;
    previouslyOpen.current = open;
  }, [open, target, files, setParams]);
  useEffect(() => {
    const check = () => {
      void useFileViewer.getState().refresh(true);
      // 窗口重新聚焦时同时校验其余标签(切走期间外部可能删了文件)
      void useFileViewer.getState().validateTabs();
    };
    window.addEventListener("focus", check);
    return () => window.removeEventListener("focus", check);
  }, []);
  const columns = open && wide && !expanded ? `minmax(340px, ${100 - width}fr) 5px minmax(480px, ${width}fr)` : "minmax(0, 1fr)";
  const resize = (event: React.PointerEvent<HTMLButtonElement>) => {
    const separator = event.currentTarget;
    separator.setPointerCapture(event.pointerId);
    const bounds = ref.current!.getBoundingClientRect();
    const move = (e: PointerEvent) => useSideWorkspace.getState().setWidth((bounds.right - e.clientX) / bounds.width * 100);
    const stop = () => { separator.removeEventListener("pointermove", move); separator.removeEventListener("pointerup", stop); separator.removeEventListener("pointercancel", stop); };
    separator.addEventListener("pointermove", move); separator.addEventListener("pointerup", stop); separator.addEventListener("pointercancel", stop);
  };
  return <WorkspaceContext.Provider value={{ sessionId, browser, tabs, activeId, selectTab, closeTab }}><main ref={ref} className="flex-1 min-w-0 min-h-0 grid overflow-hidden bg-background relative md:rounded-xl md:border md:border-border/80 md:shadow-surface" style={{ gridTemplateColumns: columns }}>
    <div className={`min-w-0 min-h-0 h-full flex flex-col overflow-hidden ${open && (!wide || expanded) ? "hidden" : ""}`}>{children}</div>
    {open && wide && !expanded && <button type="button" role="separator" aria-label="调整工作区宽度" aria-orientation="vertical" aria-valuenow={width} aria-valuemin={45} aria-valuemax={70} className="bg-border hover:bg-blue-400 focus-visible:bg-blue-500 cursor-col-resize touch-none outline-none" onPointerDown={resize} onKeyDown={event => {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        useSideWorkspace.getState().setWidth(width + (event.key === "ArrowLeft" ? 2 : -2));
      }
    }} />}
    {open && <Suspense fallback={<div className="bg-card p-6 text-sm text-muted-foreground">加载工作区…</div>}><WorkspacePanel returnFocus={focus.opener} /></Suspense>}
  </main></WorkspaceContext.Provider>;
}
