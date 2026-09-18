'use client';

import { FileText } from "lucide-react";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * text 画廊:长文报告/分析结论。
 *
 * 数据字段:
 *   title/summary/note(共享)
 *   text: "Markdown 正文"
 */
export function TextGallery({ data }: { data: Record<string, unknown> }) {
  const text = typeof data.text === "string" ? data.text : "";
  if (text.trim() === "") return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
      actions={data.actions as Array<{ label: string; prompt: string }> | undefined}
    >
      <SectionLabel icon={FileText} label={(data.sectionTitle as string) || "报告"} />
      <div className="rounded-md border border-border/60 bg-muted/20 px-2.5 py-2 text-[11px] text-foreground leading-relaxed whitespace-pre-wrap break-words">
        {text}
      </div>
    </GalleryShell>
  );
}
