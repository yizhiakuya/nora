import { Link } from "react-router-dom";
import { FileText, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import { fetchChatRuns } from "@/lib/api/agentApi";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { usePollingResource } from "@/hooks/usePollingResource";
import { useFileViewer } from "@/hooks/useFileViewer";
import { chatRunStatusLabel } from "@/lib/chatRunStatus";

async function loadRecentResults() {
  const [runs, artifacts] = await Promise.all([
    fetchChatRuns(["completed", "partial", "failed", "cancelled"], 5), savedArtifactsApi.list(5),
  ]);
  return { runs, artifacts };
}

export function RecentResults() {
  const { data, error, loading, refresh } = usePollingResource(loadRecentResults);
  return <div className="space-y-4">
    {error && <div role="alert" className="surface p-4 space-y-2"><p className="text-sm whitespace-pre-wrap break-words">{error}</p>
      {data && <p className="text-xs text-muted-foreground">以下为上次成功读取的结果。</p>}<Button variant="outline" size="sm" onClick={refresh}>重试</Button></div>}
    {loading && <p role="status" className="text-sm text-muted-foreground">正在读取最近结果…</p>}
    {data && <>
      <div className="work-list">
        {data.runs.length === 0 && <p className="p-4 text-sm text-muted-foreground">还没有已结束的运行。在首页描述目标即可开始。</p>}
        {data.runs.map(run => <Link key={run.id} to={`/chat?session=${encodeURIComponent(run.sessionId)}`} className="work-row min-h-[61px]">
          <MessageSquare className="h-3.5 w-3.5 shrink-0 text-muted-foreground" /><span className="min-w-0 flex-1 text-[13px] truncate">{run.sessionTitle || run.content || run.sessionId}</span>
          <span className={`shrink-0 rounded-md bg-muted/60 px-1.5 py-0.5 text-[11px] ${run.status === "failed" || run.status === "partial" ? "text-amber-700 dark:text-amber-300" : "text-muted-foreground"}`}>{chatRunStatusLabel(run.status)}</span>
        </Link>)}
      </div>
      <div className="flex items-center justify-between pt-2"><h3 className="section-heading">已保存成果</h3><Link to="/files?view=results" className="text-xs text-muted-foreground hover:text-foreground">查看全部 →</Link></div>
      <div className="work-list">
        {data.artifacts.length === 0 && <p className="p-4 text-sm text-muted-foreground">尚未保存成果。对话中保存的文件和知识文档会出现在这里。</p>}
        {data.artifacts.map(artifact => <div key={artifact.id} className="work-row">
          <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
          {artifact.kind === "workspace_file" ? <button type="button" className="min-w-0 flex-1 truncate text-left text-sm hover:underline" onClick={() => { void useFileViewer.getState().openTargets([`workspace:${artifact.path}`], undefined, { sessionId: artifact.sessionId ?? undefined }); }}>{artifact.name}</button>
            : <Link className="min-w-0 flex-1 truncate text-sm hover:underline" to={`/files?view=knowledge&doc=${encodeURIComponent(artifact.path)}`}>{artifact.name}</Link>}
          {artifact.sessionId && <Link className="text-xs text-primary shrink-0 hover:underline" to={`/chat?session=${encodeURIComponent(artifact.sessionId)}`}>来源会话</Link>}
        </div>)}
      </div>
    </>}
  </div>;
}
