'use client';

import { KeyRound, ExternalLink } from "lucide-react";
import { openArtifactLink, parseOpen } from "@/lib/artifacts";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * keyvalue 画廊:单对象详情(键值两列,如新建的数据源配置/服务注册信息)。
 *
 * 数据字段:
 *   title/summary/note(共享)
 *   items: [{ name(键), meta(值), open?(可选深链) }]
 */
interface KeyValueItem {
  name?: string;
  meta?: string;
  open?: string;
}

export function KeyValueGallery({ data }: { data: Record<string, unknown> }) {
  const items = (Array.isArray(data.items) ? (data.items as KeyValueItem[]) : [])
    .filter((it) => it && it.name != null);
  if (items.length === 0) return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
      actions={data.actions as Array<{ label: string; prompt: string }> | undefined}
    >
      <SectionLabel icon={KeyRound} label={(data.sectionTitle as string) || "详情"} />
      <div className="rounded-md border border-border/60 overflow-hidden">
        {items.map((item, i) => {
          const clickable = !!parseOpen(item.open);
          return (
            <button
              key={i}
              type="button"
              disabled={!clickable}
              onClick={() => openArtifactLink(item.open)}
              className={`w-full flex items-center gap-2 px-2 py-1.5 text-left ${i > 0 ? "border-t border-border/50" : ""} ${
                clickable ? "hover:bg-muted/50 cursor-pointer" : "cursor-default"
              }`}
            >
              <span className="text-[11px] text-muted-foreground w-24 shrink-0 truncate">{item.name}</span>
              <span className="text-[11px] text-foreground font-medium truncate min-w-0 flex-1">{item.meta ?? "—"}</span>
              {clickable && <ExternalLink className="w-3 h-3 text-muted-foreground/50 shrink-0" />}
            </button>
          );
        })}
      </div>
    </GalleryShell>
  );
}
