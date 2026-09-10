import { useCallback, useEffect, useState } from "react";
import { FileText, FolderOpen, HardDrive, Save, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { workspaceApi, type WorkspaceEntry, type WorkspaceStats } from "@/lib/services/workspaceApi";
import { toast } from "sonner";

/**
 * Agent 工作区面板:agent 的文件系统私有空间(也是它的长期记忆)。
 *
 * - 上:概览(路径 / 文件数 / 字节数)与记忆分层说明;
 * - 左:文件列表(目录 & 文件,可点击);
 * - 右:文件内容查看 / 编辑 / 保存(讨论记忆内容或审计 agent 写入的直接入口)。
 *
 * 设计对齐 OpenClaw workspace:AGENTS/SOUL/USER/MEMORY 每轮自动注入,
 * memory/ 日记按需读取;此处可人工检查与修正 agent 的记忆。
 */
export function WorkspaceSettings() {
  const [stats, setStats] = useState<WorkspaceStats | null>(null);
  const [entries, setEntries] = useState<WorkspaceEntry[]>([]);
  const [currentDir, setCurrentDir] = useState<string | undefined>(undefined);
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState("");
  const [original, setOriginal] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [s, list] = await Promise.all([
      workspaceApi.getStats().catch(() => null),
      workspaceApi.listFiles(currentDir).catch(() => []),
    ]);
    setStats(s);
    setEntries(list);
  }, [currentDir]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const openFile = async (path: string) => {
    try {
      const text = await workspaceApi.readFile(path);
      setSelected(path);
      setContent(text);
      setOriginal(text);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "读取失败");
    }
  };

  const handleSave = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await workspaceApi.writeFile(selected, content);
      setOriginal(content);
      toast.success("已保存");
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "保存失败");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!selected) return;
    if (!window.confirm(`确认删除「${selected}」？该操作不可恢复。`)) return;
    try {
      await workspaceApi.deleteFile(selected);
      toast.success("已删除");
      setSelected(null);
      setContent("");
      setOriginal("");
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  const dirty = selected != null && content !== original;
  const formatBytes = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

  return (
    <div className="space-y-4">
      <div className="bg-card rounded-xl border border-border shadow-sm p-5 space-y-4">
        <div className="flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-amber-50 dark:bg-amber-950/40 flex items-center justify-center shrink-0">
            <HardDrive className="w-4 h-4 text-amber-600 dark:text-amber-400" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-bold text-foreground">Agent 工作区</div>
            <p className="text-xs text-muted-foreground mt-1">
              AI 的文件系统私有空间，也是它的长期记忆载体。
              <span className="text-foreground/80">USER.md</span>（偏好）、
              <span className="text-foreground/80">MEMORY.md</span>（耐久事实）每轮自动注入；
              <span className="text-foreground/80">memory/</span> 日记按需读取，不占每轮上下文。
              AI 在对话中会自行读写这些文件，你可以在这里检查与修正。
            </p>
          </div>
        </div>
        {stats && (
          <div className="grid grid-cols-2 gap-3 text-xs">
            <div className="bg-muted/40 rounded-lg p-3 min-w-0">
              <div className="text-muted-foreground mb-0.5">工作区路径</div>
              <div className="text-foreground font-mono text-[11px] truncate" title={stats.root}>{stats.root}</div>
            </div>
            <div className="bg-muted/40 rounded-lg p-3 flex items-center gap-4">
              <div>
                <div className="text-muted-foreground mb-0.5">文件数</div>
                <div className="text-foreground font-medium">{stats.files}</div>
              </div>
              <div>
                <div className="text-muted-foreground mb-0.5">占用</div>
                <div className="text-foreground font-medium">{formatBytes(stats.bytes)}</div>
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="grid grid-cols-[260px_1fr] gap-4">
        {/* 文件列表 */}
        <div className="bg-card rounded-xl border border-border shadow-sm overflow-hidden">
          <div className="px-3 py-2 border-b border-border flex items-center gap-2">
            <FolderOpen className="w-3.5 h-3.5 text-muted-foreground" />
            <span className="text-xs font-bold text-foreground flex-1">文件</span>
            {currentDir && (
              <button
                type="button"
                className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline"
                onClick={() => { setCurrentDir(undefined); }}
              >
                返回根目录
              </button>
            )}
          </div>
          <div className="max-h-[420px] overflow-y-auto custom-scroll p-1.5">
            {entries.length === 0 && (
              <div className="text-[11px] text-muted-foreground text-center py-6">（空）</div>
            )}
            {entries.map((e) => (
              <button
                key={e.path}
                type="button"
                onClick={() => {
                  if (e.directory) {
                    setCurrentDir(e.path);
                  } else {
                    void openFile(e.path);
                  }
                }}
                className={`w-full flex items-center gap-2 px-2.5 py-1.5 rounded-md text-left transition-colors ${
                  selected === e.path
                    ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300"
                    : "hover:bg-muted/60 text-foreground"
                }`}
              >
                {e.directory ? (
                  <FolderOpen className="w-3.5 h-3.5 text-amber-500 shrink-0" />
                ) : (
                  <FileText className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                )}
                <span className="text-xs truncate flex-1">{e.path.split("/").pop()}</span>
                {!e.directory && <span className="text-[10px] text-muted-foreground/60 shrink-0">{e.size}B</span>}
              </button>
            ))}
          </div>
        </div>

        {/* 文件内容 */}
        <div className="bg-card rounded-xl border border-border shadow-sm overflow-hidden flex flex-col">
          {selected == null ? (
            <div className="flex-1 flex items-center justify-center text-xs text-muted-foreground py-24">
              从左侧选择文件查看/编辑
            </div>
          ) : (
            <>
              <div className="px-3 py-2 border-b border-border flex items-center gap-2">
                <span className="text-xs font-mono text-foreground flex-1 truncate" title={selected}>{selected}</span>
                {dirty && <span className="text-[10px] text-amber-600 dark:text-amber-400">未保存</span>}
                <Button
                  size="sm"
                  className="h-7 text-xs"
                  disabled={!dirty || busy}
                  onClick={() => void handleSave()}
                >
                  <Save className="w-3 h-3 mr-1" /> 保存
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs bg-card text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40"
                  onClick={() => void handleDelete()}
                >
                  <Trash2 className="w-3 h-3" />
                </Button>
              </div>
              <textarea
                className="flex-1 min-h-[420px] p-3 text-xs font-mono leading-relaxed bg-background text-foreground resize-none focus:outline-none custom-scroll"
                value={content}
                onChange={(e) => setContent(e.target.value)}
                spellCheck={false}
              />
            </>
          )}
        </div>
      </div>
    </div>
  );
}
