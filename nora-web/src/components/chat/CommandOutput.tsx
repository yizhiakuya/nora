import { useEffect, useRef } from "react";
import { useCommandOutput } from "@/hooks/useCommandExecutions";
import { useFileViewer } from "@/hooks/useFileViewer";
import { commandExecutionsApi } from "@/lib/services/commandExecutionsApi";
import type { CommandExecution } from "@/types";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";

export const commandStatus: Record<CommandExecution["status"], string> = {
  running: "运行中", completed: "已完成", failed: "失败", timed_out: "已超时", cancelled: "已停止", unknown: "结果未知",
};
export function commandDuration(milliseconds: number) {
  const seconds = Math.floor(milliseconds / 1000);
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export function CommandOutput({ execution }: { execution: CommandExecution }) {
  const { snapshot, error } = useCommandOutput(execution);
  const ref = useRef<HTMLPreElement>(null);
  const follow = useRef(true);
  useEffect(() => { if (follow.current && ref.current) ref.current.scrollTop = ref.current.scrollHeight; }, [snapshot.output]);
  const stop = async () => {
    try { await commandExecutionsApi.stop(snapshot.sessionId, snapshot.executionId); }
    catch (error) { toast.error(error instanceof Error ? error.message : String(error)); }
  };
  return <div className="space-y-2 min-w-0">
    <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
      <span>{commandStatus[snapshot.status]} · {commandDuration(snapshot.elapsedMs)}</span>
      <span>总时限 {commandDuration(snapshot.timeoutSeconds * 1000)}</span>
      {snapshot.status === "running" && <span>距上次输出 {commandDuration(snapshot.startedAt + snapshot.elapsedMs - snapshot.lastOutputAt)}</span>}
      {snapshot.exitCode != null && <span>exit {snapshot.exitCode}</span>}
      <div className="ml-auto flex gap-1">
        <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={() => void useFileViewer.getState().openTargets([snapshot.logTarget], undefined, { sessionId: snapshot.sessionId })}>完整日志</Button>
        {snapshot.status === "running" && <Button variant="outline" size="sm" className="h-7 px-2 text-xs" onClick={() => void stop()}>停止</Button>}
      </div>
    </div>
    <pre ref={ref} aria-label="命令输出" onScroll={() => { const el = ref.current; if (el) follow.current = el.scrollHeight - el.scrollTop - el.clientHeight < 32; }}
      className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-zinc-950 px-3 py-2.5 font-mono text-[11px] leading-relaxed text-zinc-200 custom-scroll">
      {snapshot.output || (snapshot.status === "running" ? "等待输出…" : "无输出")}
    </pre>
    {(snapshot.error || error) && <p role="alert" className="text-xs text-destructive whitespace-pre-wrap break-words">{error ?? snapshot.error}</p>}
  </div>;
}
