'use client';

import { ListTree, ExternalLink } from "lucide-react";
import { openArtifactLink, parseOpen } from "@/lib/artifacts";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * list 画廊:通用条目兜底(不确定用哪种时的安全选择,永不报错)。
 *
 * 数据字段:
 *   title/summary/stats/note(共享)
 *   items: [{ name, caption, meta, open? }]
 */
interface ListItem {
  name?: string;
  caption?: string;
  meta?: string;
  open?: string;
}

export function ListGallery({ data }: { data: Record<string, unknown> }) {
  const items = (Array.isArray(data.items) ? (data.items as ListItem[]) : [])
    .filter((it) => it && (it.name || it.caption));
  if (items.length === 0) return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
    >
      <SectionLabel icon={ListTree} label={(data.sectionTitle as string) || "条目"} count={items.length} />
      <div className="space-y-1">
        {items.map((item, i) => {
          const clickable = !!parseOpen(item.open);
          return (
            <button
              key={i}
              type="button"
              disabled={!clickable}
              onClick={() => openArtifactLink(item.open)}
              className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-md border border-border/60 bg-muted/20 text-left transition-colors ${
                clickable ? "hover:bg-muted/60 cursor-pointer" : "cursor-default"
              }`}
            >
              <span className="text-[11px] font-medium text-foreground truncate min-w-0">{item.name ?? item.caption}</span>
              {item.name && item.caption && (
                <span className="text-[10px] text-muted-foreground truncate min-w-0 hidden sm:inline">{item.caption}</span>
              )}
              <span className="flex-1" />
              {item.meta && <span className="text-[10px] text-muted-foreground/70 tabular-nums shrink-0">{item.meta}</span>}
              {clickable && <ExternalLink className="w-3 h-3 text-muted-foreground/50 shrink-0" />}
            </button>
          );
        })}
      </div>
    </GalleryShell>
  );
}
