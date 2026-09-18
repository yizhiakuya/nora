'use client';

import { useMemo, useState } from "react";
import { Images, Play } from "lucide-react";
import { ImageLightbox, type LightboxImage } from "@/components/shared/ImageLightbox";
import { mediaCacheUrl, originalVariant, thumbVariant, videoStreamVariant } from "@/lib/mediaCache";

/**
 * media 画廊:相册/媒体展示(**恢复原版设计**,2026-09-18 用户要求)。
 *
 * 设计沿革:这是手机相册 photos_showcase 的原始画廊效果(蓝色图标/N 项计数/
 * 「点击查看」提示/视频缩略图带播放角标),用户明确要求保留。v4 架构下它是
 * media 业务的独立画廊组件——协议仍走统一围栏,仅渲染恢复原版。
 *
 * 数据字段(兼容两种来源):
 *   title(标题)/ note(底部注释)
 *   items: [{ kind(image|video), url(灯箱用原图/压缩流), thumbUrl(网格缩略图),
 *             fullUrl(可选原图), name, caption, meta(拍摄时间/大小) }]
 *   旧 nora-gallery 由解析层转换后同构(url=原图, thumbUrl=缩略图)。
 */
interface MediaItem {
  kind?: string;
  url?: string;
  thumbUrl?: string;
  fullUrl?: string;
  name?: string;
  caption?: string;
  meta?: string;
  takenAt?: string;
}

/** "2026-09-04T15:53:19.502+08:00[Asia/Shanghai]" → "09-04 15:53"(解析失败原样返回)。 */
function formatTakenAt(takenAt?: string): string | undefined {
  if (!takenAt) return undefined;
  const cleaned = takenAt.replace(/\[[^\]]*\]$/, "");
  const d = new Date(cleaned);
  if (Number.isNaN(d.getTime())) return takenAt;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function MediaGallery({ data }: { data: Record<string, unknown> }) {
  // items 按 data.items 引用缓存:此前每次渲染都重建数组 → 灯箱 images 每次
  // 重建 → ImageLightbox 预加载 effect 反复解绑重挂(preload 警告刷屏)
  const items = useMemo(
    () => (Array.isArray(data.items) ? (data.items as MediaItem[]) : [])
      .filter((it) => it && (it.url || it.fullUrl)),
    [data.items],
  );
  const count = items.length;
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  const lightboxImages: LightboxImage[] = useMemo(
    () => items.map((item) => {
      const isVideo = item.kind === "video";
      const full = item.fullUrl ?? item.url ?? "";
      return {
        // 视频走压缩流端点(/video,手机端按网络档位转码);图片走原图
        // (originalVariant:/thumb 升级为 /content——灯箱全屏看缩略图必模糊)
        src: mediaCacheUrl(isVideo ? videoStreamVariant(full) : originalVariant(full)),
        thumb: mediaCacheUrl(item.thumbUrl ?? item.url ?? full),
        caption: item.caption,
        alt: item.name ?? "媒体",
        kind: isVideo ? ("video" as const) : ("image" as const),
        // 下载/新窗口出口:图片同样升级为原图档(与 src 同口径)
        originalSrc: mediaCacheUrl(isVideo ? full.replace(/(\/photo\/\d+\/)video(\?|$)/, "$1content$2") : originalVariant(full)),
      };
    }),
    [items],
  );

  if (items.length === 0) return null;

  return (
    <div className="rounded-xl border border-border bg-card overflow-hidden max-w-2xl animate-in fade-in slide-in-from-bottom-1">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/70">
        <Images className="w-3.5 h-3.5 text-blue-500 dark:text-blue-400 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{String(data.title ?? "媒体画廊")}</span>
        <span className="text-[10px] text-muted-foreground tabular-nums shrink-0">{count} 项</span>
        <span className="ml-auto text-[10px] text-muted-foreground/60 shrink-0 hidden sm:inline">
          点击查看
        </span>
      </div>
      {/* 摘要/统计(可选;2026-09-18:原版视觉不变,但 AI 写的数据不浪费) */}
      {typeof data.summary === "string" && data.summary !== "" && (
        <div className="px-3 pt-2 text-[11px] text-muted-foreground leading-relaxed">{data.summary}</div>
      )}
      {Array.isArray(data.stats) && (data.stats as unknown[]).length > 0 && (
        <div className="flex flex-wrap gap-2 px-3 pt-2">
          {(data.stats as Array<{ label: string; value: string }>).map((s, i) => (
            <div key={i} className="rounded-lg border border-border/70 bg-muted/30 px-2.5 py-1.5 min-w-[4.5rem]">
              <div className="text-sm font-bold text-foreground tabular-nums leading-tight">{s.value}</div>
              <div className="text-[10px] text-muted-foreground">{s.label}</div>
            </div>
          ))}
        </div>
      )}
      <div className="grid grid-cols-3 gap-1.5 p-2 max-h-96 overflow-auto custom-scroll">
        {items.map((item, idx) => {
          const when = formatTakenAt(item.meta ?? item.takenAt);
          const tip = [item.caption, when, item.name].filter(Boolean).join(" · ");
          return (
            <button
              type="button"
              key={idx}
              title={tip || "媒体"}
              onClick={() => setLightboxIndex(idx)}
              className="group/img relative block w-full rounded-md overflow-hidden border border-border/60 bg-muted/40 cursor-zoom-in text-left"
            >
              <img
                src={mediaCacheUrl(thumbVariant(item.thumbUrl ?? item.url ?? item.fullUrl ?? ""))}
                alt={item.caption ?? item.name ?? "媒体"}
                loading="lazy"
                className="aspect-square w-full object-cover transition-transform duration-200 group-hover/img:scale-[1.03]"
              />
              {item.kind === "video" && (
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
      {typeof data.note === "string" && data.note !== "" && (
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
