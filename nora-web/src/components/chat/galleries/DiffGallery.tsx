'use client';

import { GitCompareArrows } from "lucide-react";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * diff 画廊:设置/配置变更(改前→改后,红删绿增)。
 *
 * 数据字段:
 *   title/summary/note(共享)
 *   items: [{ name(被改项), before(改前值), after(改后值), caption, meta }]
 */
interface DiffItem {
  name?: string;
  before?: string;
  after?: string;
  caption?: string;
  meta?: string;
}

export function DiffGallery({ data }: { data: Record<string, unknown> }) {
  const items = (Array.isArray(data.items) ? (data.items as DiffItem[]) : [])
    .filter((it) => it && (it.before != null || it.after != null));
  if (items.length === 0) return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
      actions={data.actions as Array<{ label: string; prompt: string }> | undefined}
    >
      <SectionLabel icon={GitCompareArrows} label={(data.sectionTitle as string) || "变更"} count={items.length} />
      <div className="space-y-1.5">
        {items.map((item, i) => (
          <div key={i} className="rounded-md border border-border/60 bg-muted/20 px-2 py-1.5">
            {item.name && (
              <div className="text-[11px] font-medium text-foreground mb-1 flex items-center gap-1.5">
                <span className="truncate">{item.name}</span>
                {item.meta && <span className="text-[10px] text-muted-foreground/70 shrink-0">{item.meta}</span>}
              </div>
            )}
            <div className="flex items-stretch gap-1.5 text-[11px]">
              <div className="flex-1 min-w-0 rounded bg-red-50 dark:bg-red-950/30 border border-red-200/60 dark:border-red-900/50 px-1.5 py-1 text-red-700 dark:text-red-300 font-mono break-all">
                {item.before ?? "—"}
              </div>
              <div className="shrink-0 self-center text-muted-foreground/60">→</div>
              <div className="flex-1 min-w-0 rounded bg-green-50 dark:bg-green-950/30 border border-green-200/60 dark:border-green-900/50 px-1.5 py-1 text-green-700 dark:text-green-300 font-mono break-all">
                {item.after ?? "—"}
              </div>
            </div>
            {item.caption && <div className="text-[10px] text-muted-foreground mt-1">{item.caption}</div>}
          </div>
        ))}
      </div>
    </GalleryShell>
  );
}
