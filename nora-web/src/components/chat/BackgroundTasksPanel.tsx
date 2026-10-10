import { useEffect, useState } from "react";
import { ChevronDown, Loader2, Terminal, Trash2 } from "lucide-react";
import { useCommandExecutions } from "@/hooks/useCommandExecutions";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";
import { commandExecutionsApi } from "@/lib/services/commandExecutionsApi";
import type { CommandExecution } from "@/types";
import { Button } from "@/components/ui/button";
import { CommandOutput, commandDuration, commandStatus } from "./CommandOutput";
import { toast } from "sonner";

function Task({ execution, open, toggle }: { execution: CommandExecution; open: boolean; toggle: () => void }) {
  return <article id={`command-${execution.executionId}`} className="rounded-xl border border-border bg-card overflow-hidden">
    <button type="button" aria-expanded={open} onClick={toggle} className="flex w-full gap-2.5 px-3.5 py-3 text-left hover:bg-muted/50">
      {execution.status === "running" ? <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin text-primary" /> : <Terminal className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />}
      <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium" title={execution.title}>{execution.title}</p>
        <p className="mt-1 flex flex-wrap gap-x-2 text-[11px] text-muted-foreground"><span>{execution.shell === "powershell" ? "PowerShell" : "Shell"}</span><span className={execution.status === "failed" || execution.status === "timed_out" ? "text-destructive" : ""}>{commandStatus[execution.status]}</span><span className="tabular-nums">{commandDuration(execution.elapsedMs)}</span></p>
      </div><ChevronDown className={`h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform ${open ? "" : "-rotate-90"}`} />
    </button>
    {open && <div className="space-y-3 border-t border-border p-3.5">
      <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-muted/60 p-2.5 text-[11px] font-mono custom-scroll">{execution.command}</pre>
      <p className="truncate text-[11px] text-muted-foreground" title={execution.cwd}>{execution.cwd}</p>
      <CommandOutput execution={execution} />
    </div>}
  </article>;
}
export default function BackgroundTasksPanel({ sessionId }: { sessionId: string }) {
  const { items, error } = useCommandExecutions();
  const focused = useSideWorkspace(s => s.focusedCommand);
  const [opened, setOpened] = useState<Set<string>>(new Set());
  useEffect(() => { if (focused) setOpened(previous => new Set(previous).add(focused)); }, [focused]);
  const task = (item: CommandExecution) => <Task key={item.executionId} execution={item} open={opened.has(item.executionId)} toggle={() => setOpened(previous => {
    const next = new Set(previous);
    if (next.has(item.executionId)) next.delete(item.executionId); else next.add(item.executionId);
    return next;
  })} />;
  const running = items.filter(item => item.status === "running");
  const finished = items.filter(item => item.status !== "running");
  const clear = async () => {
    try {
      await commandExecutionsApi.clear(sessionId);
      useCommandExecutions.setState({ items: await commandExecutionsApi.list(sessionId) });
    } catch (error) { toast.error(error instanceof Error ? error.message : String(error)); }
  };
  return <div className="flex-1 min-h-0 overflow-y-auto custom-scroll p-4 space-y-5">
    {error && <p role="alert" className="whitespace-pre-wrap break-words text-xs text-destructive">{error}</p>}
    {!items.length && !error && <div className="py-12 text-center text-sm text-muted-foreground"><Terminal className="mx-auto mb-3 h-6 w-6" />此对话还没有命令任务<p className="mt-2 text-xs">长命令会在后台继续运行，随时可以查看输出或停止。</p></div>}
    {!!running.length && <section className="space-y-2"><h3 className="px-1 text-xs font-medium text-muted-foreground">运行中 · {running.length}</h3>{running.map(task)}</section>}
    {!!finished.length && <section className="space-y-2"><div className="flex items-center justify-between px-1"><h3 className="text-xs font-medium text-muted-foreground">已结束 · {finished.length}</h3><Button variant="ghost" size="icon" className="h-7 w-7" aria-label="清空已结束任务" title="清空列表，保留对话中的命令记录与日志" onClick={() => void clear()}><Trash2 className="h-3.5 w-3.5" /></Button></div>{finished.map(task)}</section>}
  </div>;
}
