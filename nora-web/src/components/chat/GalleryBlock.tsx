import { useState } from "react";
import { Images, Play } from "lucide-react";
import { ImageLightbox } from "@/components/shared/ImageLightbox";
import { mediaCacheUrl } from "@/lib/mediaCache";

/**
 * 画廊卡片：手机相册 MCP 的 photos_showcase 工具输出的结构化展示。
 *
 * 数据形态（工具结果里的 ```nora-gallery 围栏 JSON，见 McpProtocol.kt）：
 * 标题/每张说明/底部注释都由 agent 填写——本组件只负责渲染，
 * 不做任何筛选或推断（展示什么 = 模型确认了什么）。
 */

export interface GalleryItem {
  id: number;
  /** 缩略图（网格用，加载快） */
  url: string;
  /** 原图（点击放大打开） */
  fullUrl?: string;
  filename?: string;
  type?: string;
  takenAt?: string;
  width?: number;
  height?: number;
  /** agent 填写的单张说明 */
  caption?: string;
}

export interface GalleryData {
  version?: number;
  title: string;
  count?: number;
  items: GalleryItem[];
  /** agent 填写的底部补充说明（筛选口径/排除项等） */
  note?: string;
}

/** 从工具结果文本里提取 ```nora-gallery 围栏（无则 null）。 */
export function parseGalleryFence(content: string | null | undefined): GalleryData | null {
  if (!content) return null;
  const m = content.match(/```nora-gallery\s*\n([\s\S]*?)```/);
  return m ? parseGalleryJson(m[1]) : null;
}

/** 解析围栏内的 JSON；结构不完整/坏数据返回 null（调用方降级为普通代码块，不丢内容）。 */
export function parseGalleryJson(raw: string): GalleryData | null {
  try {
    const data = JSON.parse(raw) as GalleryData;
    if (!data || typeof data.title !== "string" || !Array.isArray(data.items) || data.items.length === 0) {
      return null;
    }
    return data;
  } catch {
    return null;
  }
}

/** "2026-09-04T15:53:19.502+08:00[Asia/Shanghai]" → "09-04 15:53"（解析失败返回 null）。 */
function formatTakenAt(takenAt?: string): string | null {
  if (!takenAt) return null;
  const cleaned = takenAt.replace(/\[[^\]]*\]$/, "");
  const d = new Date(cleaned);
  if (Number.isNaN(d.getTime())) return null;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function GalleryBlock({ data }: { data: GalleryData }) {
  const count = data.count ?? data.items.length;
  // 点击缩略图 → 页内灯箱放大（不再跳外部标签页）；视频条目走 <video> 播放
  // 媒体 URL 经后端磁盘缓存代理(/api/media/cache):一次拉取跨会话复用,
  // 手机离线也能看已缓存内容(直连时每次 ~3.2s 且离线不可用)
  // lightboxIndex=null 表示关闭
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const lightboxImages = data.items.map((item) => ({
    src: mediaCacheUrl(item.fullUrl ?? item.url),
    thumb: mediaCacheUrl(item.url),
    caption: item.caption,
    alt: item.filename ?? `照片 ${item.id}`,
    kind: item.type === "video" ? ("video" as const) : ("image" as const),
  }));

  return (
    <div className="rounded-xl border border-border bg-card overflow-hidden max-w-2xl animate-in fade-in slide-in-from-bottom-1">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/70">
        <Images className="w-3.5 h-3.5 text-blue-500 dark:text-blue-400 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{data.title}</span>
        <span className="text-[10px] text-muted-foreground tabular-nums shrink-0">{count} 项</span>
        <span className="ml-auto text-[10px] text-muted-foreground/60 shrink-0 hidden sm:inline">
          点击查看
        </span>
      </div>
      <div className="grid grid-cols-3 gap-1.5 p-2 max-h-96 overflow-auto custom-scroll">
        {data.items.map((item, idx) => {
          const when = formatTakenAt(item.takenAt);
          const tip = [item.caption, when, item.filename].filter(Boolean).join(" · ");
          return (
            <button
              type="button"
              key={item.id}
              title={tip || `照片 ${item.id}`}
              onClick={() => setLightboxIndex(idx)}
              className="group/img relative block w-full rounded-md overflow-hidden border border-border/60 bg-muted/40 cursor-zoom-in text-left"
            >
              <img
                src={mediaCacheUrl(item.url)}
                alt={item.caption ?? item.filename ?? `照片 ${item.id}`}
                loading="lazy"
                className="aspect-square w-full object-cover transition-transform duration-200 group-hover/img:scale-[1.03]"
              />
              {item.type === "video" && (
                <span className="absolute top-1 right-1 w-4 h-4 rounded-full bg-black/55 flex items-center justify-center">
                  <Play className="w-2.5 h-2.5 text-white fill-white" />
                </span>
              )}
              {(item.caption || when) && (
                <span className="absolute inset-x-0 bottom-0 px-1.5 py-1 text-[10px] leading-tight text-white bg-gradient-to-t from-black/70 to-transparent truncate">
                  {item.caption ?? when}
                </span>
              )}
            </button>
          );
        })}
      </div>
      {data.note && (
        <div className="px-3 py-2 border-t border-border/70 text-[11px] text-muted-foreground leading-relaxed">
          {data.note}
        </div>
      )}
      {lightboxIndex !== null && (
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
