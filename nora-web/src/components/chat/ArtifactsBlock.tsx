'use client';

import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Play, FileText, Folder, Link2, ExternalLink, Images, FileCheck2, ListTree } from "lucide-react";
import { ImageLightbox, type LightboxImage } from "@/components/shared/ImageLightbox";
import { mediaCacheUrl, thumbVariant, videoStreamVariant } from "@/lib/mediaCache";
import {
  itemKind,
  parseOpen,
  type ArtifactItem,
  type ArtifactSection,
  type ArtifactsData,
} from "@/lib/artifacts";

/**
 * 通用产物画廊(2026-09-18 v2,原生渲染)——与手机相册画廊同款体验。
 *
 * 设计:AI 只声明「产出了什么」(```nora-artifacts JSON),前端用 Nora 原生
 * 组件渲染:媒体走 ImageLightbox 灯箱(同手机画廊)、文件点击打开文件页/工作区。
 * 风格与工作台一体,AI 不碰样式——解决 v1(H5 自写页面)质量不可控的问题。
 */
export function ArtifactsBlock({ data }: { data: ArtifactsData }) {
  const navigate = useNavigate();
  /** 当前打开的媒体条目(跨 section 汇总;null=关闭) */
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  // 全部媒体条目(跨 section 汇总为一条灯箱序列,←/→ 连续浏览)
  const mediaItems = useMemo(
    () => data.sections.flatMap((s) => s.items.filter((it) => {
      const k = itemKind(it);
      return (k === "image" || k === "video") && (it.url || it.fullUrl);
    })),
    [data.sections],
  );

  const lightboxImages: LightboxImage[] = useMemo(
    () => mediaItems.map((item) => {
      const kind = itemKind(item);
      const full = item.fullUrl ?? item.url ?? "";
      const isVideo = kind === "video";
      return {
        // 视频走压缩流端点(手机端按网络档位转码);图片走原图
        src: mediaCacheUrl(isVideo ? videoStreamVariant(full) : full),
        thumb: mediaCacheUrl(item.thumbUrl ?? item.url ?? full),
        caption: item.caption,
        alt: item.name ?? "产物",
        kind: isVideo ? ("video" as const) : ("image" as const),
        originalSrc: mediaCacheUrl(isVideo ? full.replace(/(\/photo\/\d+\/)video(\?|$)/, "$1content$2") : full),
      };
    }),
    [mediaItems],
  );

  /** 打开条目深链:workspace:→ 工作区浏览器;file:<id> → 文件页预览;url:→ 新窗口。 */
  const openItem = (item: ArtifactItem) => {
    const parsed = parseOpen(item.open);
    if (!parsed) return;
    if (parsed.scheme === "workspace") {
      navigate(`/files?workspace=${encodeURIComponent(parsed.target)}`);
    } else if (parsed.scheme === "file") {
      navigate(`/files?open=${encodeURIComponent(parsed.target)}`);
    } else if (parsed.scheme === "url") {
      window.open(parsed.target, "_blank", "noopener");
    }
  };

  /** 媒体条目的灯箱序号(相对全量媒体序列)。 */
  const mediaIndexOf = (item: ArtifactItem): number => {
    const i = mediaItems.indexOf(item);
    return i >= 0 ? i : 0;
  };

  return (
    <div className="rounded-xl border border-border bg-card overflow-hidden max-w-2xl animate-in fade-in slide-in-from-bottom-1">
      {/* 标题区 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/70">
        <FileCheck2 className="w-3.5 h-3.5 text-emerald-500 dark:text-emerald-400 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{data.title}</span>
        <span className="ml-auto text-[10px] text-muted-foreground/60 shrink-0 hidden sm:inline">
          操作产物
        </span>
      </div>
      {data.summary && (
        <div className="px-3 pt-2 text-[11px] text-muted-foreground leading-relaxed">{data.summary}</div>
      )}

      {/* 统计条 */}
      {data.stats && data.stats.length > 0 && (
        <div className="flex flex-wrap gap-2 px-3 pt-2.5">
          {data.stats.map((s, i) => (
            <div key={i} className="rounded-lg border border-border/70 bg-muted/30 px-2.5 py-1.5 min-w-[4.5rem]">
              <div className="text-sm font-bold text-foreground tabular-nums leading-tight">{s.value}</div>
              <div className="text-[10px] text-muted-foreground">{s.label}</div>
            </div>
          ))}
        </div>
      )}

      {/* 分组内容 */}
      <div className="px-3 py-2.5 space-y-3">
        {data.sections.map((section, si) => (
          <SectionView
            key={si}
            section={section}
            onOpenItem={openItem}
            onOpenMedia={(item) => setLightboxIndex(mediaIndexOf(item))}
          />
        ))}
      </div>

      {data.note && (
        <div className="px-3 py-2 border-t border-border/70 text-[11px] text-muted-foreground leading-relaxed">
          {data.note}
        </div>
      )}

      {lightboxIndex !== null && lightboxImages.length > 0 && (
        <ImageLightbox
          images={lightboxImages}
          index={lightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onIndexChange={setLightboxIndex}
        />
      )}
    </div>
  );
}

/** 单个分组:media=媒体网格 / files=文件列表 / list=通用条目。 */
function SectionView({
  section,
  onOpenItem,
  onOpenMedia,
}: {
  section: ArtifactSection;
  onOpenItem: (item: ArtifactItem) => void;
  onOpenMedia: (item: ArtifactItem) => void;
}) {
  const kind = section.kind ?? "list";
  const Icon = kind === "media" ? Images : kind === "files" ? Folder : ListTree;
  return (
    <div>
      {section.title && (
        <div className="flex items-center gap-1.5 mb-1.5">
          <Icon className="w-3 h-3 text-muted-foreground/70 shrink-0" />
          <span className="text-[11px] font-medium text-muted-foreground">{section.title}</span>
          <span className="text-[10px] text-muted-foreground/50 tabular-nums">{section.items.length}</span>
        </div>
      )}
      {kind === "media" ? (
        <div className="grid grid-cols-3 gap-1.5 max-h-96 overflow-auto custom-scroll">
          {section.items.map((item, i) => (
            <MediaCell key={i} item={item} onOpen={() => onOpenMedia(item)} />
          ))}
        </div>
      ) : (
        <div className="space-y-1">
          {section.items.map((item, i) => (
            <FileRow key={i} item={item} onOpen={() => onOpenItem(item)} />
          ))}
        </div>
      )}
    </div>
  );
}

/** 媒体格子(缩略图 + 视频角标 + caption,点击开灯箱——与手机画廊同款)。 */
function MediaCell({ item, onOpen }: { item: ArtifactItem; onOpen: () => void }) {
  const kind = itemKind(item);
  const isVideo = kind === "video";
  const thumb = mediaCacheUrl(thumbVariant(item.thumbUrl ?? item.url ?? item.fullUrl ?? ""));
  return (
    <button
      type="button"
      title={[item.caption, item.meta, item.name].filter(Boolean).join(" · ")}
      onClick={onOpen}
      className="group/img relative block w-full rounded-md overflow-hidden border border-border/60 bg-muted/40 cursor-zoom-in text-left"
    >
      {kind === "image" ? (
        <img
          src={thumb}
          alt={item.caption ?? item.name ?? "产物"}
          loading="lazy"
          className="aspect-square w-full object-cover transition-transform duration-200 group-hover/img:scale-[1.03]"
        />
      ) : (
        <span className="aspect-square w-full flex items-center justify-center bg-zinc-900/90">
          <Play className="w-5 h-5 text-white/85 fill-white/85" />
        </span>
      )}
      {(item.caption || item.meta) && (
        <span className="absolute inset-x-0 bottom-0 px-1.5 py-1 text-[10px] leading-tight text-white bg-gradient-to-t from-black/70 to-transparent truncate">
          {item.caption ?? item.meta}
        </span>
      )}
    </button>
  );
}

/** 文件行(图标 + 名称 + 元信息;有 open 深链时可点击,右侧给箭头)。 */
function FileRow({ item, onOpen }: { item: ArtifactItem; onOpen: () => void }) {
  const kind = itemKind(item);
  const Icon = kind === "folder" ? Folder : kind === "link" ? Link2 : FileText;
  const clickable = !!parseOpen(item.open);
  const parsed = parseOpen(item.open);
  return (
    <button
      type="button"
      disabled={!clickable}
      onClick={onOpen}
      title={clickable ? "点击打开" : undefined}
      className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-md border border-border/60 bg-muted/20 text-left transition-colors ${
        clickable ? "hover:bg-muted/60 cursor-pointer" : "cursor-default"
      }`}
    >
      <Icon className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
      <span className="text-[11px] font-medium text-foreground truncate min-w-0">{item.name ?? item.url}</span>
      {item.caption && (
        <span className="text-[10px] text-muted-foreground truncate min-w-0 hidden sm:inline">{item.caption}</span>
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
