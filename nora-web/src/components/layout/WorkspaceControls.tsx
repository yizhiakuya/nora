import { lazy, Suspense, useRef, useState } from "react";
import { Check, FileText, Globe, PanelRightClose, PanelRightOpen, Plus, Terminal } from "lucide-react";
import { useWorkspace } from "./WorkspaceContext";
import { useCommandExecutions } from "@/hooks/useCommandExecutions";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";
import { useFileViewer } from "@/hooks/useFileViewer";
import { useChatSessions } from "@/hooks/useChatSessions";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

const WebTaskDialog = lazy(() => import("@/components/browser/WebTaskDialog"));

export function WorkspaceControls() {
  const { sessionId, browser, tabs, activeId, selectTab } = useWorkspace();
  const open = useSideWorkspace(state => state.isOpen);
  const running = useCommandExecutions(state => state.items.filter(item => item.status === "running").length);
  const showTasks = () => useFileViewer.getState().requestAction(() => useSideWorkspace.getState().show("tasks"));
  const [taskOpen, setTaskOpen] = useState(false);
  const pending = useRef<(() => void) | null>(null);
  const openBrowser = () => {
    if (!sessionId) useChatSessions.getState().createSession();
    browser.show();
  };
  const label = open ? "收起工作区" : "打开工作区";
  return <>
    <div className="flex items-center gap-1">
      <Button variant="ghost" size="icon" className={`h-8 w-8 shrink-0 ${open ? "bg-accent text-accent-foreground" : "text-muted-foreground"}`}
        title={label} aria-label={label} aria-expanded={open} aria-controls="side-workspace"
        onClick={() => { if (open) useFileViewer.getState().close(); else if (tabs.length) useSideWorkspace.setState({ isOpen: true }); else openBrowser(); }}>
        {open ? <PanelRightClose className="w-4 h-4" /> : <PanelRightOpen className="w-4 h-4" />}
      </Button>
      {running > 0 && <Button variant="ghost" size="sm" className="h-8 gap-1.5 px-2 text-xs text-primary" aria-label={`后台任务，${running} 个运行中`} onClick={showTasks}><Terminal className="h-3.5 w-3.5" /><span className="tabular-nums">{running}</span></Button>}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0 text-muted-foreground" title="已打开的标签页" aria-label={`已打开的标签页：${tabs.length}`}>
            <span className="inline-flex h-4 min-w-4 items-center justify-center rounded border border-current px-0.5 text-[10px] tabular-nums">{tabs.length}</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" collisionPadding={12} className="w-64 max-w-[calc(100vw-24px)]" onCloseAutoFocus={() => {
          // 菜单先恢复入口焦点，侧面板或表单关闭后才能回到同一入口。
          const action = pending.current; pending.current = null; action?.();
        }}>
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">已打开的标签页</DropdownMenuLabel>
          <div className="max-h-64 overflow-y-auto">
            {tabs.map(tab => <DropdownMenuItem key={tab.id} disabled={tab.disabled} onSelect={() => { pending.current = () => selectTab(tab.id); }}>
              {tab.kind === "browser" ? <Globe className="w-4 h-4 shrink-0" /> : tab.kind === "tasks" ? <Terminal className="w-4 h-4 shrink-0" /> : <FileText className="w-4 h-4 shrink-0" />}
              <span className="truncate flex-1" title={tab.title}>{tab.title}</span>{activeId === tab.id && <Check className="w-3.5 h-3.5 shrink-0" />}
            </DropdownMenuItem>)}
            {!tabs.length && <p className="px-2 py-2 text-xs text-muted-foreground">尚未打开网页或文件</p>}
          </div>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => { pending.current = showTasks; }}><Terminal className="w-4 h-4" />后台任务</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => { pending.current = openBrowser; }}><Globe className="w-4 h-4" />打开浏览器</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => { pending.current = () => setTaskOpen(true); }}><Plus className="w-4 h-4" />新建网页资料任务</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
    {taskOpen && <Suspense fallback={null}><WebTaskDialog onClose={() => setTaskOpen(false)} /></Suspense>}
  </>;
}
