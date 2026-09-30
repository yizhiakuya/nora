import { FilePenLine, Plus, Minus } from "lucide-react";
import type { ChatMessage, FileChange } from "@/lib/api/chatApi";
import { useFileViewer } from "@/hooks/useFileViewer";

/**
 * 编辑卡片(2026-09-30,对齐 Codex 的「已编辑 N 个文件」):
 * agent 写完文件后,对话下方汇总本轮全部文本写操作——头部总数 + 行级
 * 增删统计,每行可点击直接进统一文件查看器阅读改动后的内容。
 *
 * 数据来自步骤 result.fileChanges(服务端在写操作完成后计算行级 diff);
 * 同一文件多轮操作(如 write 后又 edit)按 target 合并统计。
 */
export function FileChangeCards({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  const steps = msg.steps?.filter(step => step.status === "completed" || step.status === "partial") ?? [];
  // 按 target 合并(保持首次出现顺序):同一文件的多次写操作累计行数
  const merged = new Map<string, FileChange>();
  for (const step of steps) {
    for (const change of step.result?.fileChanges ?? []) {
      const existing = merged.get(change.target);
      if (existing) {
        merged.set(change.target, {
          ...existing,
          additions: existing.additions + change.additions,
          deletions: existing.deletions + change.deletions,
          created: existing.created && change.created,
        });
      } else {
        merged.set(change.target, change);
      }
    }
  }
  const changes = Array.from(merged.values());
  if (!changes.length) return null;

  const totalAdditions = changes.reduce((sum, change) => sum + change.additions, 0);
  const totalDeletions = changes.reduce((sum, change) => sum + change.deletions, 0);
  const open = (target: string) =>
    void useFileViewer.getState().openTargets([target], target, { sessionId, collection: changes.map(change => change.target) });

  return (
    <div className="rounded-xl border border-border bg-card max-w-2xl overflow-hidden" aria-label="本轮编辑的文件">
      <div className="flex items-center gap-3 px-3 py-2.5">
        <div className="shrink-0 rounded-lg bg-muted p-1.5 text-muted-foreground">
          <FilePenLine className="w-4 h-4" />
        </div>
        <span className="text-sm font-medium flex-1">已编辑 {changes.length} 个文件</span>
        <span className="text-xs tabular-nums text-green-700 dark:text-green-400">+{totalAdditions}</span>
        <span className="text-xs tabular-nums text-red-600 dark:text-red-400">-{totalDeletions}</span>
      </div>
      <div className="border-t border-border divide-y divide-border">
        {changes.map(change => (
          <button
            key={change.target}
            type="button"
            className="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 transition-colors"
            onClick={() => open(change.target)}
            title={change.target}
          >
            <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">
              {change.target.startsWith("workspace:") ? change.target.slice(10) : change.target}
              {change.created && <span className="ml-2 inline-block align-middle rounded border border-green-300 dark:border-green-800 bg-green-50 dark:bg-green-950/40 px-1 text-[10px] text-green-700 dark:text-green-400">新建</span>}
            </span>
            <span className="shrink-0 inline-flex items-center gap-0.5 text-xs tabular-nums text-green-700 dark:text-green-400"><Plus className="w-3 h-3" />{change.additions}</span>
            <span className="shrink-0 inline-flex items-center gap-0.5 text-xs tabular-nums text-red-600 dark:text-red-400"><Minus className="w-3 h-3" />{change.deletions}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
