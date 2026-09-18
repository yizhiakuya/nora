import type { ReactNode } from "react";
import { FileCheck2 } from "lucide-react";

/**
 * 画廊外壳(所有画廊共享):卡片容器 + 标题区 + 摘要 + 统计条 + 底部注释。
 *
 * 每个画廊组件(MediaGallery/FilesGallery/...)只需实现内容区,
 * 头部/底部样式由本组件统一——保证各画廊风格一致但不互相耦合。
 */

export interface GalleryShellProps {
  title?: string;
  summary?: string;
  stats?: Array<{ label: string; value: string }>;
  note?: string;
  children: ReactNode;
}

export function GalleryShell({ title, summary, stats, note, children }: GalleryShellProps) {
  return (
    <div className="rounded-xl border border-border bg-card overflow-hidden max-w-2xl animate-in fade-in slide-in-from-bottom-1">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/70">
        <FileCheck2 className="w-3.5 h-3.5 text-emerald-500 dark:text-emerald-400 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{title || "操作产物"}</span>
        <span className="ml-auto text-[10px] text-muted-foreground/60 shrink-0 hidden sm:inline">
          操作产物
        </span>
      </div>
      {summary && (
        <div className="px-3 pt-2 text-[11px] text-muted-foreground leading-relaxed">{summary}</div>
      )}
      {stats && stats.length > 0 && (
        <div className="flex flex-wrap gap-2 px-3 pt-2.5">
          {stats.map((s, i) => (
            <div key={i} className="rounded-lg border border-border/70 bg-muted/30 px-2.5 py-1.5 min-w-[4.5rem]">
              <div className="text-sm font-bold text-foreground tabular-nums leading-tight">{s.value}</div>
              <div className="text-[10px] text-muted-foreground">{s.label}</div>
            </div>
          ))}
        </div>
      )}
      <div className="px-3 py-2.5">{children}</div>
      {note && (
        <div className="px-3 py-2 border-t border-border/70 text-[11px] text-muted-foreground leading-relaxed">
          {note}
        </div>
      )}
    </div>
  );
}

/** 分组小标题(可选;多段内容时用)。 */
export function SectionLabel({ icon: Icon, label, count }: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  count?: number;
}) {
  return (
    <div className="flex items-center gap-1.5 mb-1.5">
      <Icon className="w-3 h-3 text-muted-foreground/70 shrink-0" />
      <span className="text-[11px] font-medium text-muted-foreground">{label}</span>
      {count != null && count > 0 && (
        <span className="text-[10px] text-muted-foreground/50 tabular-nums">{count}</span>
      )}
    </div>
  );
}
