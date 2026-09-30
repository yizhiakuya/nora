import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, FileText, FolderOpen, Trash2 } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { workspaceApi, type WorkspaceEntry, type WorkspaceStats } from "@/lib/services/workspaceApi";
import { toast } from "sonner";

interface WorkspaceBrowserProps {
  /** 当前目录(相对工作区根;"" = 根目录);受控,由文件页持有 */
  dir: string;
  /** 导航到指定目录 */
  onNavigate: (dir: string) => void;
  /** 退出工作区,回到文件中心根视图 */
  onExit: () => void;
}

/**
 * Agent 工作区文件夹浏览器——作为「文件」页里的一个普通文件夹出现(文件系统一体化)。
 *
 * - 面包屑:文件中心 / Agent 工作区 / …(任意层级可点击跳回);
 * - 目录进入 / 文件打开共享查看器;
 * - 底部标注真实磁盘路径,强调它就是文件系统上的一个目录。
 */
export function WorkspaceBrowser({ dir, onNavigate, onExit }: WorkspaceBrowserProps) {
  const [entries, setEntries] = useState<WorkspaceEntry[]>([]);
  const [stats, setStats] = useState<WorkspaceStats | null>(null);

  const refresh = useCallback(async () => {
    const list = await workspaceApi.listFiles(dir).catch(() => []);
    setEntries(list);
  }, [dir]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    workspaceApi.getStats().then(setStats).catch(() => { /* 统计不可用不阻塞浏览 */ });
  }, []);

  const handleDelete = async (path: string) => {
    if (!window.confirm(`确认删除「${path}」？该操作不可恢复。`)) return;
    try {
      await workspaceApi.deleteFile(path);
      toast.success("已删除");
      // 删除成功即标记查看器标签失效(打开中的文件立即提示,不等用户刷新)
      useFileViewer.getState().markMissing([`workspace:${path}`]);
      if (useFileViewer.getState().active?.target === `workspace:${path}`) void useFileViewer.getState().refresh();
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  const segments = dir ? dir.split("/").filter(Boolean) : [];
  const goUp = () => onNavigate(segments.slice(0, -1).join("/"));

  const dirs = entries.filter((e) => e.directory);
  const files = entries.filter((e) => !e.directory);
  const realPath = stats ? `${stats.root}${dir ? `\\${dir.replace(/\//g, "\\")}` : ""}` : null;

  return (
    <>
      {/* 路径面包屑:文件中心 / Agent 工作区 / …(点击跳转;末级为当前目录) */}
      <div className="flex items-center gap-1.5 mb-4 text-xs animate-in fade-in flex-wrap">
        <button
          type="button"
          className="text-muted-foreground hover:text-foreground transition-colors"
          onClick={onExit}
        >
          文件中心
        </button>
        <span className="text-muted-foreground/50">/</span>
        <button
          type="button"
          className={segments.length === 0 ? "text-foreground font-medium" : "text-muted-foreground hover:text-foreground transition-colors"}
          onClick={() => onNavigate("")}
        >
          Agent 工作区
        </button>
        {segments.map((seg, i) => (
          <span key={i} className="flex items-center gap-1.5">
            <span className="text-muted-foreground/50">/</span>
            <button
              type="button"
              className={i === segments.length - 1 ? "text-foreground font-medium" : "text-muted-foreground hover:text-foreground transition-colors"}
              onClick={() => onNavigate(segments.slice(0, i + 1).join("/"))}
            >
              {seg}
            </button>
          </span>
        ))}
      </div>

      <div className="bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        {segments.length > 0 && (
          <button
            type="button"
            onClick={goUp}
            className="w-full flex items-center gap-3 px-4 py-2.5 border-b border-border hover:bg-muted/50 transition-colors text-left"
          >
            <ArrowLeft className="w-4 h-4 text-muted-foreground shrink-0" />
            <span className="text-sm text-muted-foreground">返回上一级</span>
          </button>
        )}
        {entries.length === 0 && (
          <div className="py-16 text-center text-xs text-muted-foreground">（空目录）</div>
        )}
        {[...dirs, ...files].map((e) => (
          <div key={e.path} className="flex border-b border-border last:border-0">
          <button
            type="button"
            onClick={() => e.directory ? onNavigate(e.path) : void useFileViewer.getState().openTargets([`workspace:${e.path}`], undefined, { collection: files.map(file => `workspace:${file.path}`) })}
            className="flex-1 min-w-0 flex items-center gap-3 px-4 py-2.5 hover:bg-muted/50 transition-colors text-left"
          >
            {e.directory ? (
              <FolderOpen className="w-4 h-4 text-amber-500 shrink-0" />
            ) : (
              <FileText className="w-4 h-4 text-muted-foreground shrink-0" />
            )}
            <span className="text-sm text-foreground flex-1 truncate">{e.path.split("/").pop()}</span>
            <span className="text-xs text-muted-foreground">{e.directory ? "目录" : `${e.size} B`}</span>
            <span className="text-xs text-muted-foreground/60 hidden sm:inline">{e.modifiedAt}</span>
          </button>
          {!e.directory && <button type="button" aria-label={`删除 ${e.path}`} className="px-3 text-muted-foreground hover:text-red-500" onClick={() => useFileViewer.getState().requestAction(() => { void handleDelete(e.path); })}><Trash2 className="w-3.5 h-3.5" /></button>}
          </div>
        ))}
      </div>

      {/* 真实磁盘路径:工作区就是文件系统上的一个真实目录 */}
      {realPath && (
        <div className="mt-3 text-[10px] text-muted-foreground/70 font-mono truncate" title={realPath}>
          真实目录:{realPath}
          {stats ? ` · 共 ${stats.files} 个文件 ${stats.bytes} B` : ""}
        </div>
      )}

    </>
  );
}
