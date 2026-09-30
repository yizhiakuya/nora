import { lazy, Suspense, useEffect, useRef, type ReactNode } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import { useFileViewer } from "@/hooks/useFileViewer";

const FileViewerPanel = lazy(() => import("@/components/files/viewer/FileViewerPanel"));

export function FileViewerWorkspace({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const open = useFileViewer(state => state.isOpen);
  const wide = useFileViewer(state => state.wide);
  const expanded = useFileViewer(state => state.expanded);
  const width = useFileViewer(state => state.width);
  const [params, setParams] = useSearchParams();
  const target = params.get("viewer");
  const location = useLocation();
  const lastTarget = useRef("");
  const previouslyOpen = useRef(open);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(entries => useFileViewer.setState({ wide: entries[0].contentRect.width >= 840 }));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!target) { lastTarget.current = ""; return; }
    const key = `${location.pathname}:${target}`;
    if (lastTarget.current === key) return;
    lastTarget.current = key;
    void useFileViewer.getState().openTargets([target], target);
  }, [target, location.pathname]);
  useEffect(() => {
    if (previouslyOpen.current && !open && target) {
      setParams(previous => { const next = new URLSearchParams(previous); next.delete("viewer"); return next; }, { replace: true });
    }
    previouslyOpen.current = open;
  }, [open, target, setParams]);
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
    const move = (e: PointerEvent) => useFileViewer.getState().setWidth((bounds.right - e.clientX) / bounds.width * 100);
    const stop = () => { separator.removeEventListener("pointermove", move); separator.removeEventListener("pointerup", stop); separator.removeEventListener("pointercancel", stop); };
    separator.addEventListener("pointermove", move); separator.addEventListener("pointerup", stop); separator.addEventListener("pointercancel", stop);
  };
  return <main ref={ref} className="flex-1 min-w-0 min-h-0 grid overflow-hidden bg-background relative" style={{ gridTemplateColumns: columns }}>
    <div className={`min-w-0 min-h-0 h-full flex flex-col overflow-hidden ${open && (!wide || expanded) ? "hidden" : ""}`}>{children}</div>
    {open && wide && !expanded && <button type="button" role="separator" aria-label="调整文件查看器宽度" aria-orientation="vertical" aria-valuenow={width} aria-valuemin={45} aria-valuemax={70} className="bg-border hover:bg-blue-400 focus-visible:bg-blue-500 cursor-col-resize touch-none outline-none" onPointerDown={resize} onKeyDown={event => {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        useFileViewer.getState().setWidth(width + (event.key === "ArrowLeft" ? 2 : -2));
      }
    }} />}
    {open && <Suspense fallback={<div className="bg-card p-6 text-sm text-muted-foreground">加载文件查看器…</div>}><FileViewerPanel /></Suspense>}
  </main>;
}
