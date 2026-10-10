import { lazy, Suspense, useEffect, useRef } from "react";
import { AlertTriangle, ArrowLeft, FileText, Globe, Maximize2, Minimize2, Terminal, X } from "lucide-react";
import { useWorkspace } from "./WorkspaceContext";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";
import { useFileViewer } from "@/hooks/useFileViewer";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { NavigationToggle } from "./NavigationToggle";

const BackgroundTasksPanel = lazy(() => import("@/components/chat/BackgroundTasksPanel"));
const BrowserPanel = lazy(() => import("@/components/browser/BrowserPanel"));
const FileViewerPanel = lazy(() => import("@/components/files/viewer/FileViewerPanel"));

export default function WorkspacePanel() {
  const { sessionId, browser, tabs, activeId, selectTab, closeTab } = useWorkspace();
  const viewer = useFileViewer();
  const workspace = useSideWorkspace();
  const ref = useRef<HTMLElement>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    returnFocus.current ??= document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = ref.current;
    panel?.focus();
    return () => { requestAnimationFrame(() => {
      if (!panel?.isConnected && returnFocus.current?.isConnected) returnFocus.current.focus();
    }); };
  }, []);
  return <Tabs value={activeId} onValueChange={selectTab} asChild>
    <aside id="side-workspace" ref={ref} tabIndex={-1} aria-label="侧面工作区" className="h-full min-h-0 min-w-0 flex flex-col bg-card border-l border-border outline-none"
      onPointerDownCapture={viewer.suppressAutoOpen} onKeyDownCapture={viewer.suppressAutoOpen} onWheelCapture={viewer.suppressAutoOpen}
      onKeyDown={event => {
        const target = event.target as HTMLElement;
        if (event.key === "Escape" && !event.defaultPrevented && !target.matches("input, textarea, select, video, audio") && !target.isContentEditable) {
          event.preventDefault();
          if (workspace.expanded) workspace.setExpanded(false); else viewer.close();
        }
      }}>
      <header className="h-14 shrink-0 flex items-center gap-1 px-2 border-b border-border/80">
        <NavigationToggle />
        {!workspace.wide && <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label="返回原页面" onClick={viewer.close}><ArrowLeft className="w-4 h-4" /></Button>}
        <TabsList aria-label="工作区标签页" className="min-w-0 flex-1 h-full rounded-none bg-transparent p-0 gap-0.5">
          {tabs.map(tab => <div key={tab.id} className={`shrink-0 flex items-center rounded-lg ${activeId === tab.id ? "bg-accent" : ""}`}>
            <TabsTrigger value={tab.id} disabled={tab.disabled} title={tab.title} className="gap-1.5 max-w-[180px] px-2 py-2 text-xs data-[state=active]:bg-transparent data-[state=active]:shadow-none">
              {tab.missing ? <AlertTriangle className="w-3.5 h-3.5 shrink-0 text-amber-600" aria-label="文件已失效" /> : tab.kind === "browser" ? <Globe className="w-3.5 h-3.5 shrink-0" /> : tab.kind === "tasks" ? <Terminal className="w-3.5 h-3.5 shrink-0" /> : <FileText className="w-3.5 h-3.5 shrink-0" />}
              <span className={`truncate ${tab.missing ? "line-through opacity-60" : ""}`}>{tab.title}</span>
            </TabsTrigger>
            <Button variant="ghost" size="icon" className="h-6 w-6 mr-1 shrink-0 text-muted-foreground" aria-label={`关闭标签页 ${tab.title}`}
              disabled={tab.kind === "browser" && !!browser.state?.active && (browser.busy || browser.state.owner !== "human")} onClick={() => closeTab(tab.id)}><X className="w-3 h-3" /></Button>
          </div>)}
        </TabsList>
        {workspace.wide && <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label={workspace.expanded ? "恢复并排显示" : "展开工作区"} onClick={() => workspace.setExpanded(!workspace.expanded)}>{workspace.expanded ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}</Button>}
        <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label="收起工作区" onClick={viewer.close}><X className="w-4 h-4" /></Button>
      </header>
      <TabsContent value={activeId} forceMount className="mt-0 flex-1 min-h-0 min-w-0 flex flex-col">
        <Suspense fallback={<div className="p-5 text-xs text-muted-foreground">加载内容…</div>}>
          {workspace.tasksOpened && <div className={`flex-1 min-h-0 flex flex-col ${workspace.panel === "tasks" ? "" : "hidden"}`}><BackgroundTasksPanel key={sessionId} sessionId={sessionId} /></div>}
          {(viewer.status !== "idle" || viewer.tabs.length > 0) && <div className={`flex-1 min-h-0 ${workspace.panel === "file" ? "" : "hidden"}`}><FileViewerPanel /></div>}
          {(workspace.browserOpened || browser.state?.active) && <div className={`flex-1 min-h-0 ${workspace.panel === "browser" ? "" : "hidden"}`}><BrowserPanel sessionId={sessionId} browser={browser} /></div>}
        </Suspense>
      </TabsContent>
    </aside>
  </Tabs>;
}
