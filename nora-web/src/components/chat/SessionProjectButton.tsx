import { useEffect, useState } from "react";
import { FolderOpen, ChevronDown } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { SessionProjectDialog } from "./SessionProjectDialog";
import { bindSessionProject, fetchSessionProject, type SessionProject } from "@/lib/api/agentApi";
import { humanizeError } from "@/lib/errorMessages";

export function SessionProjectButton({ sessionId, disabled }: { sessionId: string; disabled: boolean }) {
  const [project, setProject] = useState<SessionProject>();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    setProject(undefined); setLoadError(undefined);
    void fetchSessionProject(sessionId).then(value => { if (active) setProject(value); })
      .catch(error => { if (active) setLoadError(humanizeError(error).message); });
    return () => { active = false; };
  }, [sessionId, revision]);
  const save = async (directory: string) => {
    setSaving(true);
    try { setProject(await bindSessionProject(sessionId, directory)); setOpen(false); }
    catch (error) { toast.error(humanizeError(error).message); }
    finally { setSaving(false); }
  };
  return <>
    <Button variant="ghost" size="sm" className="h-7 min-w-0 max-w-full gap-1.5 text-muted-foreground px-2" aria-label="执行环境与项目"
      title={project ? `${project.environment} · ${project.platform}\n${project.projectRoot}` : "会话项目"}
      onClick={() => { if (!project) { setRevision(value => value + 1); return; } setOpen(true); }}><FolderOpen className="h-4 w-4 shrink-0" />
      <span className="min-w-0 truncate text-xs font-normal">{project ? project.projectRoot.split(/[\\/]/).filter(Boolean).at(-1) || project.projectRoot : loadError ? "重试读取执行环境" : "正在读取执行环境…"}</span>
      {project && <span className="hidden sm:inline shrink-0 border-l border-border pl-2 text-[11px] font-normal">{project.platform}</span>}<ChevronDown className="!h-3 !w-3 shrink-0" />
    </Button>
    {loadError && <p role="alert" className="px-2 text-xs whitespace-pre-wrap break-words text-destructive">{loadError}</p>}
    {open && project && <SessionProjectDialog key={sessionId} sessionId={sessionId} project={project} disabled={disabled} saving={saving}
      onClose={() => { if (!saving) setOpen(false); }} onSave={directory => void save(directory)} />}
  </>;
}
