'use client';

import { FileText, Folder, Link2, ExternalLink, FolderTree } from "lucide-react";
import { openArtifactLink, parseOpen } from "@/lib/artifacts";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * files 画廊:文件增删改/归档(文件行,点击深链直达)。
 *
 * 数据字段:
 *   title/summary/stats/note(共享)
 *   items: [{ kind(file|folder|link), name, note(动作说明), caption, meta(大小/数量),
 *             open(workspace:路径 | file:id | url:链接) }]
 */
interface FileItem {
  kind?: string;
  name?: string;
  note?: string;
  caption?: string;
  meta?: string;
  open?: string;
}

export function FilesGallery({ data }: { data: Record<string, unknown> }) {
  const items = (Array.isArray(data.items) ? (data.items as FileItem[]) : [])
    .filter((it) => it && (it.name || it.caption));
  if (items.length === 0) return null;

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
    >
      <SectionLabel icon={FolderTree} label={(data.sectionTitle as string) || "文件"} count={items.length} />
      <div className="space-y-1">
        {items.map((item, i) => (
          <FileRow key={i} item={item} />
        ))}
      </div>
    </GalleryShell>
  );
}

function FileRow({ item }: { item: FileItem }) {
  const Icon = item.kind === "folder" ? Folder : item.kind === "link" ? Link2 : FileText;
  const clickable = !!parseOpen(item.open);
  const parsed = parseOpen(item.open);
  return (
    <button
      type="button"
      disabled={!clickable}
      onClick={() => openArtifactLink(item.open)}
      title={clickable ? "点击打开" : undefined}
      className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-md border border-border/60 bg-muted/20 text-left transition-colors ${
        clickable ? "hover:bg-muted/60 cursor-pointer" : "cursor-default"
      }`}
    >
      <Icon className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
      <span className="text-[11px] font-medium text-foreground truncate min-w-0">{item.name ?? "—"}</span>
      {(item.note || item.caption) && (
        <span className="text-[10px] text-muted-foreground truncate min-w-0 hidden sm:inline">
          {item.note ?? item.caption}
        </span>
      )}
      <span className="flex-1" />
      {item.meta && <span className="text-[10px] text-muted-foreground/70 tabular-nums shrink-0">{item.meta}</span>}
      {clickable && (
        parsed?.scheme === "url"
          ? <ExternalLink className="w-3 h-3 text-muted-foreground/50 shrink-0" />
          : <ExternalLink className="w-3 h-3 text-muted-foreground/50 shrink-0 rotate-180" />
      )}
    </button>
  );
}
