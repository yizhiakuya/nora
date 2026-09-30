import { Download, FileText, MessageSquarePlus, Pencil, RefreshCw, Save } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { useChatSessions } from "@/hooks/useChatSessions";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { viewerApi } from "@/lib/services/viewerApi";
import { workspaceApi } from "@/lib/services/workspaceApi";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { toast } from "sonner";
import { contentHashSuffix } from "@/lib/saveState";

export function ViewerToolbar() {
  const viewer = useFileViewer();
  const activeSession = useChatSessions(state => state.activeId);
  const navigate = useNavigate();
  const file = viewer.active;
  if (!file) return null;
  const temporary = file.target.startsWith("history:");
  const attach = () => {
    const session = viewer.origin.sessionId || activeSession || useChatSessions.getState().createSession();
    viewer.attach(session);
    if (window.location.pathname !== "/chat" || activeSession !== session) {
      useChatSessions.getState().setActive(session);
      navigate(`/chat?session=${encodeURIComponent(session)}`);
    }
  };
  const saveTemporary = async () => {
    const path = `reports/${file.name.replace(/\.[^.]+$/, "").replace(/[\\/:*?"<>|]/g, "_")}-${contentHashSuffix(viewer.preview?.text ?? "")}.md`;
    try {
      await workspaceApi.writeFile(path, viewer.preview?.text ?? "");
      const registered = await savedArtifactsApi.register({ kind: "workspace_file", path, name: path.split("/").pop()!, sessionId: viewer.origin.sessionId });
      if (!registered) toast.warning("文件已保存，成果登记失败");
      else toast.success("已保存为文件");
      await viewer.openTargets([`workspace:${path}`], undefined, viewer.origin);
    } catch (error) { toast.error(error instanceof Error ? error.message : "保存失败"); }
  };
  return (
    <div className="shrink-0 flex flex-wrap items-center justify-between gap-2 px-4 py-3 border-b border-border">
      {file.capabilities.source && !viewer.editing && <div className="flex p-0.5 bg-muted rounded-lg" aria-label="内容显示方式">
        {(["preview", "source"] as const).map(mode => <button key={mode} type="button" onClick={() => viewer.setMode(mode)} aria-pressed={viewer.mode === mode} className={`px-3 py-1.5 text-xs rounded-md ${viewer.mode === mode ? "bg-card text-foreground shadow-sm" : "text-muted-foreground"}`}>{mode === "preview" ? "预览" : "源码"}</button>)}
      </div>}
      {viewer.editing && <span className="text-xs text-amber-700 dark:text-amber-300">编辑中{viewer.draft !== viewer.preview?.text ? " · 未保存" : ""}</span>}
      <div className="flex items-center flex-wrap gap-1 ml-auto">
        {viewer.editing ? <>
          <Button size="sm" variant="ghost" onClick={viewer.cancelEdit} disabled={viewer.saving}>取消编辑</Button>
          <Button size="sm" onClick={() => void viewer.save()} disabled={viewer.saving}><Save className="w-3.5 h-3.5 mr-1" />保存</Button>
        </> : <>
          {file.capabilities.attach && <Button size="sm" variant="ghost" onClick={attach} className="text-blue-600 dark:text-blue-400"><MessageSquarePlus className="w-3.5 h-3.5 mr-1" />引用到对话</Button>}
          {file.capabilities.edit && viewer.status === "ready" && !viewer.preview?.truncated && <Button size="sm" variant="ghost" onClick={viewer.beginEdit}><Pencil className="w-3.5 h-3.5 mr-1" />编辑</Button>}
          {!temporary && <Button size="icon" variant="ghost" className="h-8 w-8" aria-label="刷新文件" onClick={() => void viewer.refresh()}><RefreshCw className="w-3.5 h-3.5" /></Button>}
          {file.capabilities.download && <a href={viewerApi.rawUrl(file, true)} download={file.name} className="inline-flex items-center gap-1 rounded-md px-2 py-1.5 text-xs hover:bg-muted" aria-label={`下载 ${file.name}`}><Download className="w-3.5 h-3.5" />下载</a>}
          {temporary && <Button size="sm" variant="ghost" onClick={() => void saveTemporary()}><FileText className="w-3.5 h-3.5 mr-1" />保存为文件</Button>}
        </>}
      </div>
    </div>
  );
}
