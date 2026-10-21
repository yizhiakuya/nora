import { useEffect, useState } from "react";
import { ArrowUp, ChevronRight, Folder, HardDrive, Loader2, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { fetchProjectDirectories, type ProjectDirectories, type SessionProject } from "@/lib/api/agentApi";
import { humanizeError } from "@/lib/errorMessages";

export function SessionProjectDialog({ sessionId, project, disabled, saving, onClose, onSave }: {
  sessionId: string; project: SessionProject; disabled: boolean; saving: boolean;
  onClose: () => void; onSave: (path: string) => void;
}) {
  const [location, setLocation] = useState({ path: project.projectRoot });
  const [pathInput, setPathInput] = useState(project.projectRoot);
  const [filter, setFilter] = useState("");
  const [view, setView] = useState<ProjectDirectories>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const navigate = (path: string) => { setLoading(true); setError(undefined); setFilter(""); setPathInput(path); setLocation({ path }); };
  useEffect(() => {
    let active = true;
    void fetchProjectDirectories(sessionId, location.path)
      .then(value => { if (active) { setView(value); setPathInput(value.path); } })
      .catch(failure => { if (active) setError(humanizeError(failure).message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [sessionId, location]);
  const entries = view?.directories.filter(entry => entry.name.toLocaleLowerCase().includes(filter.toLocaleLowerCase())) ?? [];
  const pathEdited = pathInput !== view?.path;
  return <Modal isOpen onClose={onClose} title="选择工作区" width="w-[620px]" closeOnOutsideClick={!saving} closeOnEscape={!saving}
    footer={<>
      <Button variant="outline" disabled={saving} onClick={onClose}>取消</Button>
      <Button disabled={disabled || saving || loading || !!error || !view || pathEdited} onClick={() => view && onSave(view.path)}>
        {saving ? "切换中…" : "使用此目录"}
      </Button>
    </>}>
    <div className="space-y-4">
      <p className="text-xs leading-relaxed text-muted-foreground">{project.environment} · {project.platform}。选择 Nora 执行任务的目录，命令和文件的相对路径将以此为准。</p>
      <div className="flex flex-wrap gap-1.5">
        <Button size="sm" variant="outline" className="h-7 gap-1.5 text-xs" disabled={saving} onClick={() => navigate(view?.defaultRoot ?? "")}><RotateCcw className="h-3.5 w-3.5" />默认工作区</Button>
        {view?.roots.map(root => <Button key={root} size="sm" variant="ghost" className="h-7 gap-1.5 text-xs" disabled={saving} onClick={() => navigate(root)}><HardDrive className="h-3.5 w-3.5" />{root}</Button>)}
      </div>
      <form className="flex items-center gap-2" onSubmit={event => { event.preventDefault(); if (!saving) navigate(pathInput); }}>
        <Button type="button" variant="outline" size="icon" className="h-9 w-9 shrink-0" aria-label="返回上级目录" disabled={saving || loading || !view?.parent} onClick={() => view?.parent && navigate(view.parent)}><ArrowUp className="h-4 w-4" /></Button>
        <Input aria-label="目录路径" value={pathInput} disabled={saving} onChange={event => setPathInput(event.target.value)} placeholder="输入路径并按 Enter 跳转" className="min-w-0 text-xs" />
        <Button type="submit" variant="outline" disabled={saving} className="shrink-0">跳转</Button>
      </form>
      <div className="overflow-hidden rounded-xl border border-border">
        <div className="border-b border-border/70 bg-muted/30 p-2"><Input aria-label="筛选当前目录" placeholder="筛选文件夹…" value={filter} disabled={saving || loading || !!error} onChange={event => setFilter(event.target.value)} className="h-8 border-0 bg-transparent shadow-none" /></div>
        <div className="h-56 overflow-y-auto" aria-busy={loading}>
          {loading ? <p role="status" className="flex items-center justify-center gap-2 p-8 text-xs text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />正在读取目录…</p>
            : error ? <div className="space-y-3 p-4"><p role="alert" className="whitespace-pre-wrap break-words text-xs text-destructive">{error}</p><Button variant="outline" size="sm" onClick={() => navigate(location.path)}>重试</Button></div>
              : entries.length ? entries.map(entry => <button type="button" key={entry.path} disabled={saving} onClick={() => navigate(entry.path)} aria-label={`打开目录 ${entry.name}`}
                className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none disabled:opacity-50">
                <Folder className="h-4 w-4 shrink-0 text-muted-foreground" /><span className="min-w-0 flex-1 truncate">{entry.name}</span><ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              </button>) : <p className="p-8 text-center text-xs text-muted-foreground">{filter ? "没有匹配的文件夹" : "此目录没有子文件夹，可直接使用此目录。"}</p>}
        </div>
      </div>
      {view?.truncated && !loading && !error && <p className="text-xs text-muted-foreground">仅列出 500 个子目录；未显示的目录可通过完整路径跳转。</p>}
      <p className="text-xs text-muted-foreground">{disabled ? "当前任务结束后可以切换工作区。" : pathEdited ? "路径已修改，跳转确认目录后即可使用。" : "浏览目录不会改变当前工作区，确认后自动加载该目录及上级的 AGENTS 规则。"}</p>
      <details className="text-xs text-muted-foreground"><summary className="cursor-pointer">当前工作区与规则</summary><div className="mt-2 space-y-1 break-all"><p>{project.projectRoot}</p>
        {project.rules.filter(rule => !rule.missing && /AGENTS(?:\.override)?\.md$/i.test(rule.path)).map(rule => <p key={rule.path}>{rule.path}{rule.truncated ? "（已截断，可按路径读取全文）" : ""}</p>)}
      </div></details>
    </div>
  </Modal>;
}
