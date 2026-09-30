'use client';

import { FileText, Maximize2 } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * text 画廊:长文报告/分析结论。
 *
 * 数据字段:
 *   title/summary/note(共享)
 *   text: "Markdown 正文"
 */
export function TextGallery({ data, sessionId }: { data: Record<string, unknown>; sessionId?: string }) {
  const text = typeof data.text === "string" ? data.text : "";
  if (text.trim() === "") return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
    >
      <SectionLabel icon={FileText} label={(data.sectionTitle as string) || "报告"} />
      <button type="button" className="mb-2 inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400" onClick={() => useFileViewer.getState().openText(String(data.title ?? "历史报告"), text, { sessionId })}><Maximize2 className="w-3 h-3" />展开阅读</button>
      <div className="rounded-md border border-border/60 bg-muted/20 px-2.5 py-2 text-[11px] text-foreground leading-relaxed whitespace-pre-wrap break-words">
        {text}
      </div>
    </GalleryShell>
  );
}
