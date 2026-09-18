'use client';

import { useMemo, useState } from "react";
import {
  Play, FileText, Folder, Link2, ExternalLink, Images, FileCheck2, ListTree,
  Table2, GitCompareArrows, ListChecks, KeyRound,
} from "lucide-react";
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
 * 通用产物画廊(2026-09-18 v3,多形态原生渲染)——与手机相册画廊同款体验。
 *
 * 设计:AI 只声明「产出了什么」(```nora-artifacts JSON),前端用 Nora 原生
 * 组件渲染:媒体走 ImageLightbox 灯箱(同手机画廊)、文件点击打开文件页/工作区。
 * 风格与工作台一体,AI 不碰样式——解决 v1(H5 自写页面)质量不可控的问题。
 *
 * v3 多形态:section 按业务选类型(media 网格/table 表格/diff 对比/timeline 时间线/
 * keyvalue 详情/text 长文/files 文件行/list 兜底),未知类型降级 list 不报错。
 *
 * 旧 ```nora-gallery 围栏由解析层转换为同一结构(历史消息兼容,组件只留一套)。
 *
 * 跳转用 window.location(非 SPA navigate):目标是文件页且携带查询参数
 * (?workspace=/?open=),整页导航语义最直白,也避免组件在 Router 外
 * (单元测试/独立预览)时的 hook 依赖。
 */
export function ArtifactsBlock({ data }: { data: ArtifactsData }) {
  /** 当前打开的媒体条目(跨 section 汇总;null=关闭) */
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  // 全部媒体条目(跨 section 汇总为一条灯箱序列,←/→ 连续浏览)
  const mediaItems = useMemo(
    () => data.sections.flatMap((s) => (s.items ?? []).filter((it) => {
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
      window.location.href = `/files?workspace=${encodeURIComponent(parsed.target)}`;
    } else if (parsed.scheme === "file") {
      window.location.href = `/files?open=${encodeURIComponent(parsed.target)}`;
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

/**
 * 单个分组渲染(多形态,2026-09-18 v3):按 section.kind 分发到对应渲染器。
 * 未知类型降级为 list(兜底不报错);无 items 且无 rows/text 的段不渲染。
 */
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
  const Icon = SECTION_ICONS[kind] ?? ListTree;
  const items = section.items ?? [];
  const count = kind === "table" ? (section.rows?.length ?? 0) : items.length;
  return (
    <div>
      {section.title && (
        <div className="flex items-center gap-1.5 mb-1.5">
          <Icon className="w-3 h-3 text-muted-foreground/70 shrink-0" />
          <span className="text-[11px] font-medium text-muted-foreground">{section.title}</span>
          {count > 0 && <span className="text-[10px] text-muted-foreground/50 tabular-nums">{count}</span>}
        </div>
      )}
      {kind === "media" ? (
        <div className="grid grid-cols-3 gap-1.5 max-h-96 overflow-auto custom-scroll">
          {items.map((item, i) => (
            <MediaCell key={i} item={item} onOpen={() => onOpenMedia(item)} />
          ))}
        </div>
      ) : kind === "table" ? (
        <TableSection section={section} />
      ) : kind === "diff" ? (
        <DiffSection items={items} />
      ) : kind === "timeline" ? (
        <TimelineSection items={items} />
      ) : kind === "keyvalue" ? (
        <KeyValueSection items={items} onOpenItem={onOpenItem} />
      ) : kind === "text" ? (
        <TextSection text={section.text ?? ""} />
      ) : (
        <div className="space-y-1">
          {items.map((item, i) => (
            <FileRow key={i} item={item} onOpen={() => onOpenItem(item)} />
          ))}
        </div>
      )}
    </div>
  );
}

/** section 类型 → 图标。 */
const SECTION_ICONS: Record<string, typeof Images> = {
  media: Images,
  files: Folder,
  table: Table2,
  diff: GitCompareArrows,
  timeline: ListChecks,
  keyvalue: KeyRound,
  text: FileText,
  list: ListTree,
};

/** table 段:列头 + 行(第一列左对齐,数字列右对齐;窄屏横向滚动)。 */
function TableSection({ section }: { section: ArtifactSection }) {
  const cols = section.columns ?? [];
  const rows = section.rows ?? [];
  if (cols.length === 0) {
    // 未给列定义:从首行 key 推断
    const first = rows[0];
    if (!first) return null;
    return (
      <div className="overflow-x-auto custom-scroll rounded-md border border-border/60">
        <table className="w-full text-[11px]">
          <thead>
            <tr className="bg-muted/40">
              {Object.keys(first).map((k) => (
                <th key={k} className="px-2 py-1.5 text-left font-medium text-muted-foreground whitespace-nowrap">{k}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-border/50">
                {Object.keys(first).map((k) => (
                  <td key={k} className="px-2 py-1 text-foreground whitespace-nowrap">{String(r[k] ?? "")}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <div className="overflow-x-auto custom-scroll rounded-md border border-border/60">
      <table className="w-full text-[11px]">
        <thead>
          <tr className="bg-muted/40">
            {cols.map((c) => (
              <th key={c.key}
                  className={`px-2 py-1.5 font-medium text-muted-foreground whitespace-nowrap ${c.align === "right" ? "text-right" : "text-left"}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-border/50">
              {cols.map((c) => (
                <td key={c.key}
                    className={`px-2 py-1 text-foreground whitespace-nowrap ${c.align === "right" ? "text-right tabular-nums" : "text-left"}`}>
                  {String(r[c.key] ?? "")}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** diff 段:每行「改前 → 改后」(红删绿增的文本对比)。 */
function DiffSection({ items }: { items: ArtifactItem[] }) {
  return (
    <div className="space-y-1">
      {items.map((item, i) => (
        <div key={i} className="rounded-md border border-border/60 bg-muted/20 px-2 py-1.5">
          {item.name && (
            <div className="text-[11px] font-medium text-foreground mb-1 flex items-center gap-1.5">
              <FileText className="w-3 h-3 text-muted-foreground shrink-0" />
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
  );
}

/** timeline 段:竖向时间线(状态点 + 名称 + 时间)。 */
function TimelineSection({ items }: { items: ArtifactItem[] }) {
  const dotClass = (status?: string) => {
    switch (status) {
      case "done": return "bg-green-500";
      case "failed": return "bg-red-500";
      case "running": return "bg-blue-500 animate-pulse";
      default: return "bg-muted-foreground/40";
    }
  };
  return (
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
  );
}

/** keyvalue 段:键值两列(单对象详情,如新建的数据源配置)。 */
function KeyValueSection({ items, onOpenItem }: { items: ArtifactItem[]; onOpenItem: (item: ArtifactItem) => void }) {
  return (
    <div className="rounded-md border border-border/60 overflow-hidden">
      {items.map((item, i) => {
        const clickable = !!parseOpen(item.open);
        return (
          <button
            key={i}
            type="button"
            disabled={!clickable}
            onClick={() => onOpenItem(item)}
            className={`w-full flex items-center gap-2 px-2 py-1.5 text-left ${i > 0 ? "border-t border-border/50" : ""} ${
              clickable ? "hover:bg-muted/50 cursor-pointer" : "cursor-default"
            }`}
          >
            <span className="text-[11px] text-muted-foreground w-24 shrink-0 truncate">{item.name}</span>
            <span className="text-[11px] text-foreground font-medium truncate min-w-0 flex-1">{item.meta}</span>
            {clickable && <ExternalLink className="w-3 h-3 text-muted-foreground/50 shrink-0" />}
          </button>
        );
      })}
    </div>
  );
}

/** text 段:Markdown 长文(报告类;轻量渲染,段落 + 换行)。 */
function TextSection({ text }: { text: string }) {
  return (
    <div className="rounded-md border border-border/60 bg-muted/20 px-2.5 py-2 text-[11px] text-foreground leading-relaxed whitespace-pre-wrap break-words">
      {text}
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
