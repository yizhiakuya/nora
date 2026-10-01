import { SOURCE_META } from "@/lib/knowledgeSourceMeta";
import type { KnowledgeDoc, KnowledgeSource } from "@/types";

export function DocIcon({ source }: { source: KnowledgeSource }) {
  const meta = SOURCE_META[source];
  const Icon = meta.icon;
  return <Icon className={`w-4 h-4 shrink-0 ${meta.color}`} />;
}

export function StatusBadge({ status, enabled }: { status: KnowledgeDoc["status"]; enabled?: boolean | null }) {
  // 停用态优先展示(阶段 B:停用=退出检索,数据保留——与失败/正常都不同)
  if (enabled === false) {
    return <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-medium border whitespace-nowrap bg-gray-50 dark:bg-gray-800 text-muted-foreground border-border">已停用</span>;
  }
  const map = {
    indexed:    { label: "已索引", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800" },
    processing: { label: "处理中", cls: "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800" },
    failed:     { label: "失败",   cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800" },
  } as const;
  const s = map[status];
  return <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-medium border whitespace-nowrap ${s.cls}`}>{s.label}</span>;
}

export function QualityBar({ score }: { score: number }) {
  if (score === 0) return <span className="text-[10px] text-muted-foreground">—</span>;
  const color = score >= 90 ? "bg-green-500" : score >= 80 ? "bg-yellow-500" : "bg-red-500";
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-12 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
        <div className={`h-full ${color}`} style={{ width: `${score}%` }} />
      </div>
      <span className="text-[10px] text-muted-foreground tabular-nums">{score}</span>
    </div>
  );
}

