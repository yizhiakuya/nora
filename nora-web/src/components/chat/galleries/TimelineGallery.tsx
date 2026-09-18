'use client';

import { ListChecks } from "lucide-react";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * timeline 画廊:任务执行/事件序列(竖向时间线,状态点)。
 *
 * 数据字段:
 *   title/summary/stats/note(共享)
 *   items: [{ name(步骤/事件), status(done|failed|running|pending), meta(时间), caption }]
 */
interface TimelineItem {
  name?: string;
  status?: string;
  meta?: string;
  caption?: string;
}

export function TimelineGallery({ data }: { data: Record<string, unknown> }) {
  const items = (Array.isArray(data.items) ? (data.items as TimelineItem[]) : [])
    .filter((it) => it && it.name);
  if (items.length === 0) return null;

  const dotClass = (status?: string) => {
    switch (status) {
      case "done": return "bg-green-500";
      case "failed": return "bg-red-500";
      case "running": return "bg-blue-500 animate-pulse";
      default: return "bg-muted-foreground/40";
    }
  };

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
      actions={data.actions as Array<{ label: string; prompt: string }> | undefined}
    >
      <SectionLabel icon={ListChecks} label={(data.sectionTitle as string) || "执行过程"} count={items.length} />
      <div className="relative pl-4 space-y-2 before:absolute before:left-[5px] before:top-1 before:bottom-1 before:w-px before:bg-border">
        {items.map((item, i) => (
          <div key={i} className="relative">
            <span className={`absolute -left-4 top-1 w-2.5 h-2.5 rounded-full border-2 border-card ${dotClass(item.status)}`} />
            <div className="flex items-baseline gap-2 min-w-0">
              <span className="text-[11px] font-medium text-foreground truncate">{item.name}</span>
              {item.meta && <span className="text-[10px] text-muted-foreground/70 tabular-nums shrink-0">{item.meta}</span>}
            </div>
            {item.caption && <div className="text-[10px] text-muted-foreground mt-0.5">{item.caption}</div>}
          </div>
        ))}
      </div>
    </GalleryShell>
  );
}
