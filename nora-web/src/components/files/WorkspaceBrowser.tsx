import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, FileText, FolderOpen, Save, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import { workspaceApi, type WorkspaceEntry } from "@/lib/services/workspaceApi";
import { toast } from "sonner";

/**
 * Agent 工作区浏览器:在「文件」页以文件树形式直接浏览 agent 的工作区
 * (也是它的长期记忆:USER.md/MEMORY.md/memory 日记)。
 *
 * - 目录导航(点目录进入,点「返回」上一级);
 * - 文件点击打开编辑器弹窗,可修改并保存——人工检查/修正 agent 记忆的入口;
 * - 与设置中心「工作区」页共享同一套 API。
 */
export function WorkspaceBrowser() {
  const [entries, setEntries] = useState<WorkspaceEntry[]>([]);
  const [currentDir, setCurrentDir] = useState<string | undefined>(undefined);
  const [editing, setEditing] = useState<string | null>(null);
  const [content, setContent] = useState("");
  const [original, setOriginal] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const list = await workspaceApi.listFiles(currentDir).catch(() => []);
    setEntries(list);
  }, [currentDir]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

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

  const goUp = () => {
    if (!currentDir) return;
    const parts = currentDir.split("/");
    parts.pop();
    setCurrentDir(parts.length ? parts.join("/") : undefined);
  };

  const dirty = editing != null && content !== original;
  const dirs = entries.filter((e) => e.directory);
  const files = entries.filter((e) => !e.directory);

  return (
    <>
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-bold text-foreground">
            {currentDir ? `Agent 工作区 / ${currentDir}` : "Agent 工作区"}
          </h2>
        </div>
        <div className="flex items-center gap-2">
          {currentDir && (
            <Button variant="outline" size="sm" className="h-8 text-xs bg-card" onClick={goUp}>
              <ArrowLeft className="w-3.5 h-3.5 mr-1" /> 上一级
            </Button>
          )}
          <span className="text-xs text-muted-foreground">{entries.length} 项</span>
        </div>
      </div>

      <div className="bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        {entries.length === 0 && (
          <div className="py-16 text-center text-xs text-muted-foreground">（空目录）</div>
        )}
        {[...dirs, ...files].map((e) => (
          <button
            key={e.path}
            type="button"
            onClick={() => (e.directory ? setCurrentDir(e.path) : void openFile(e.path))}
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
