import { useEffect, useState } from "react";
import { FileText, FolderOpen, ArrowUpRight, RefreshCw } from "lucide-react";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { workspaceApi, type WorkspaceStats, type WorkspaceEntry } from "@/lib/services/workspaceApi";
import { useFileViewer } from "@/hooks/useFileViewer";
import { humanSize } from "@/lib/services/filesApi";

const FILES = [
  { path: "SOUL.md", label: "身份与风格", description: "Nora 的角色、表达方式和行为边界。" },
  { path: "AGENTS.md", label: "工作指令", description: "执行任务时应遵守的方法、规则和约定。" },
  { path: "USER.md", label: "关于你", description: "长期偏好、工作背景和常用习惯。" },
  { path: "MEMORY.md", label: "长期记忆", description: "跨会话保留的事实、决定和经验。" },
] as const;

export function AgentMemorySettings() {
  const [data, setData] = useState<{ stats: WorkspaceStats | null; files: WorkspaceEntry[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const openTargets = useFileViewer(s => s.openTargets);
  useEffect(() => {
    let cancelled = false;
    setData(null); setError(null);
    Promise.all([workspaceApi.getStats(), workspaceApi.listFiles()])
      .then(([stats, files]) => { if (!cancelled) setData({ stats, files }); })
      .catch(error => { if (!cancelled) setError(error instanceof Error ? error.message : String(error)); });
    return () => { cancelled = true; };
  }, [version]);

  return <section className="surface overflow-hidden">
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border p-5 sm:p-6">
      <div className="min-w-0">
        <h2 className="section-heading">指令与记忆</h2>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">打开文件后可在侧面面板中编辑。保存的内容会用于后续任务的上下文。</p>
      </div>
      <Button variant="ghost" size="icon" aria-label="刷新指令与记忆" onClick={() => setVersion(v => v + 1)}><RefreshCw /></Button>
    </div>
    {error ? <div className="p-5 sm:p-6"><p role="alert" className="whitespace-pre-wrap break-words text-sm text-destructive">{error}</p></div>
      : !data ? <p className="p-6 text-xs text-muted-foreground">加载工作区…</p>
      : <>
        <div className="divide-y divide-border">
          {FILES.map(file => {
            const entry = data.files.find(entry => entry.path === file.path && !entry.directory);
            return <div key={file.path} className="flex flex-wrap items-center gap-4 p-5 sm:px-6">
              <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1 basis-40">
                <h3 className="text-sm font-medium">{file.label} <span className="ml-1 text-xs font-normal text-muted-foreground">{file.path}</span></h3>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{file.description}</p>
              </div>
              <span className="text-[11px] text-muted-foreground">{entry ? humanSize(entry.size) : "文件未创建"}</span>
              <Button variant="outline" size="sm" disabled={!entry} aria-label={`查看与编辑 ${file.path}`} onClick={() => void openTargets([`workspace:${file.path}`])}>查看与编辑<ArrowUpRight /></Button>
            </div>;
          })}
        </div>
        <div className="border-t border-border bg-muted/20 p-5 sm:px-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-xs font-medium">Agent 工作区</p>
              <p className="mt-1 break-all font-mono text-[11px] text-muted-foreground">{data.stats?.root}</p>
            </div>
            <Button variant="outline" size="sm" asChild><Link to="/files?workspace="><FolderOpen />浏览工作区</Link></Button>
          </div>
          <p className="mt-3 text-[11px] leading-relaxed text-muted-foreground">这四个文件随任务加载；memory/ 中的日记按需读取。文件保存会检查外部修改，避免覆盖 Agent 刚写入的记忆。</p>
        </div>
      </>}
  </section>;
}
