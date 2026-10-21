import { Link } from "react-router-dom";
import { AlertTriangle, Inbox, Loader2, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import { fetchChatRuns, fetchPendingActions } from "@/lib/api/agentApi";
import { usePollingResource } from "@/hooks/usePollingResource";
import { chatRunStatusLabel } from "@/lib/chatRunStatus";

async function loadActiveWork() {
  const [runs, pending] = await Promise.all([
    fetchChatRuns(["queued", "running", "awaiting_approval", "cancelling", "interrupted"], 100),
    fetchPendingActions(),
  ]);
  return { runs, pending };
}

export function RunningTasks({ compact = false }: { compact?: boolean } = {}) {
  const { data, error, loading, refresh } = usePollingResource(loadActiveWork);
  const runs = (data?.runs ?? []).filter(run => !data?.pending.some(action => action.sessionId === run.sessionId))
    .sort((a, b) => Number(a.status === "interrupted") - Number(b.status === "interrupted"));
  const visible = compact ? runs.slice(0, 5) : runs;
  return <div className="space-y-3">
    {error && <div role="alert" className="surface p-4 space-y-2"><p className="text-sm whitespace-pre-wrap break-words">{error}</p>
      <p className="text-xs text-muted-foreground">{data ? "以下为上次成功读取的状态。" : "未能读取运行状态。"}</p><Button size="sm" variant="outline" onClick={refresh}>重试</Button></div>}
    {loading && <p role="status" className="text-sm text-muted-foreground flex items-center gap-2"><Loader2 className="h-4 w-4 animate-spin" />正在检查后台任务…</p>}
    {data?.pending.map(action => <Link key={`${action.kind}:${action.sessionId}:${action.stepId}`} to={`/chat?session=${encodeURIComponent(action.sessionId)}`}
      className="block rounded-xl border border-amber-500/25 bg-amber-500/5 p-4 transition-colors hover:bg-amber-500/10">
      <div className="text-xs font-medium text-amber-700 dark:text-amber-300">{action.kind === "approval" ? "等待你确认" : "等待你回答"}</div>
      <p className="text-sm mt-1 break-words">{action.summary}</p><span className="text-xs text-muted-foreground">进入会话处理 →</span>
    </Link>)}
    {data && data.runs.length === 0 && data.pending.length === 0 && !error && <div className={`rounded-xl border border-dashed border-border text-sm text-muted-foreground flex items-center gap-2 ${compact ? "px-4 py-6" : "p-8"}`}>
      <Inbox className="h-4 w-4 shrink-0" />当前没有正在处理的任务。描述目标即可开始。</div>}
    {visible.length > 0 && <div className="work-list">{visible.map(run => {
      const interrupted = run.status === "interrupted";
      const Icon = interrupted ? AlertTriangle : Loader2;
      return <Link key={run.id} to={`/chat?session=${encodeURIComponent(run.sessionId)}`} className="work-row group">
        <Icon className={`h-4 w-4 shrink-0 ${interrupted ? "text-amber-500" : "animate-spin text-primary"}`} />
        <div className="min-w-0 flex-1"><p className="text-[13px] font-medium truncate">{run.sessionTitle || run.content || run.sessionId}</p>
          <p className="text-[11px] text-muted-foreground mt-0.5">{chatRunStatusLabel(run.status)}{interrupted ? " · 查看已保存的过程" : ""}</p></div>
        <MessageSquare className="h-3.5 w-3.5 text-muted-foreground/60 transition-colors group-hover:text-foreground" />
      </Link>;
    })}</div>}
    {compact && runs.length > visible.length && <Link to="/tasks?view=running" className="block text-xs text-primary hover:underline">查看全部运行（另有 {runs.length - visible.length} 条）</Link>}
    {data && data.runs.length === 100 && <p className="text-xs text-muted-foreground">显示最近 100 条运行。更早记录可在<Link className="underline" to="/sessions">全部会话</Link>中查找。</p>}
  </div>;
}
