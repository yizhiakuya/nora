import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, FileText, FolderOpen, Save, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
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
 * - 目录进入 / 文件打开编辑器(保存/删除);
 * - 底部标注真实磁盘路径,强调它就是文件系统上的一个目录。
 */
export function WorkspaceBrowser({ dir, onNavigate, onExit }: WorkspaceBrowserProps) {
  const [entries, setEntries] = useState<WorkspaceEntry[]>([]);
  const [stats, setStats] = useState<WorkspaceStats | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [content, setContent] = useState("");
  const [original, setOriginal] = useState("");
  const [busy, setBusy] = useState(false);

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

  const openFile = async (path: string) => {
    try {
      const text = await workspaceApi.readFile(path);
      setEditing(path);
      setContent(text);
      setOriginal(text);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "读取失败");
    }
  };

  const handleSave = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      await workspaceApi.writeFile(editing, content);
      setOriginal(content);
      toast.success("已保存");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "保存失败");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!editing) return;
    if (!window.confirm(`确认删除「${editing}」？该操作不可恢复。`)) return;
    try {
      await workspaceApi.deleteFile(editing);
      toast.success("已删除");
      setEditing(null);
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  const segments = dir ? dir.split("/").filter(Boolean) : [];
  const goUp = () => onNavigate(segments.slice(0, -1).join("/"));

  const dirty = editing != null && content !== original;
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
          <button
            key={e.path}
            type="button"
            onClick={() => (e.directory ? onNavigate(e.path) : void openFile(e.path))}
            className="w-full flex items-center gap-3 px-4 py-2.5 border-b border-border last:border-0 hover:bg-muted/50 transition-colors text-left"
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
        ))}
      </div>

      {/* 真实磁盘路径:工作区就是文件系统上的一个真实目录 */}
      {realPath && (
        <div className="mt-3 text-[10px] text-muted-foreground/70 font-mono truncate" title={realPath}>
          真实目录:{realPath}
          {stats ? ` · 共 ${stats.files} 个文件 ${stats.bytes} B` : ""}
        </div>
      )}

      <Modal
        isOpen={editing != null}
        onClose={() => setEditing(null)}
        title={editing ?? ""}
        width="w-[94%] sm:w-[720px]"
        footer={
          <>
            {dirty && <span className="text-xs text-amber-600 dark:text-amber-400 mr-auto">未保存</span>}
            <Button
              variant="outline"
              size="sm"
              className="bg-card text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40"
              onClick={() => void handleDelete()}
            >
              <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
            </Button>
            <Button size="sm" disabled={!dirty || busy} onClick={() => void handleSave()}>
              <Save className="w-3.5 h-3.5 mr-1" /> 保存
            </Button>
          </>
        }
      >
        <textarea
          className="w-full min-h-[380px] p-3 text-xs font-mono leading-relaxed bg-[#1e1e1e] text-gray-300 rounded-lg resize-none focus:outline-none custom-scroll"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          spellCheck={false}
        />
      </Modal>
    </>
  );
}
