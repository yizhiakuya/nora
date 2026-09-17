import { useCallback, useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, Download, ExternalLink, X } from "lucide-react";
import { requestJson } from "@/lib/api/client";

/**
 * 媒体灯箱：聊天里的照片/视频点击后页内放大查看，不再跳外部标签页。
 *
 * 交互（对齐常见媒体预览）：
 * - 点击遮罩 / 右上角 × / Esc 关闭；
 * - 多张时左右箭头 / ← → 键切换（单张时隐藏；视频播放中左右键由播放器接管）；
 * - 顶栏给"新窗口打开"与"下载"两个显式出口（需要时才离开页内）；
 * - `kind: "video"` 的条目用原生 <video> 播放（相册视频走压缩流端点）。
 *
 * 原片自动缓存（2026-09-17）：打开查看时后台调 `/api/media/warm`——
 * 手机在 Wi-Fi 时后端把**原片**拉入磁盘缓存（与正在看的压缩流互不冲突），
 * 之后任何网络/离线场景都能看原片；蜂窝下后端自动跳过（不偷偷烧流量）。
 */

export interface LightboxImage {
  /** 大图/视频（原图/原片）地址 */
  src: string;
  /** 列表缩略图（先用它做即时占位，大图加载完再替换，避免白屏） */
  thumb?: string;
  /** 说明文字（agent 填写的 caption 等） */
  caption?: string;
  alt?: string;
  /** 媒体类型;缺省 image。video 时用 <video controls> 渲染 */
  kind?: "image" | "video";
  /**
   * 原片地址（与播放用的压缩流区分）:视频的 src 是压缩流,下载/新窗口
   * 打开应给原片——查看时后端已自动缓存原片,此处链接直通缓存。
   */
  originalSrc?: string;
}

export function ImageLightbox({
  images,
  index,
  onClose,
  onIndexChange,
}: {
  images: LightboxImage[];
  index: number;
  onClose: () => void;
  onIndexChange: (next: number) => void;
}) {
  const [loaded, setLoaded] = useState(false);
  const current = images[index];
  const hasMultiple = images.length > 1;
  const isVideo = current?.kind === "video";

  /**
   * 原片自动缓存：打开/切换到某媒体时调一次 /api/media/warm。
   * 后端仅在手机处于 Wi-Fi 时执行（蜂窝下跳过，不偷偷烧流量）；
   * fire-and-forget——失败不影响查看（缓存是增益，不是依赖）。
   *
   * 缓存目标是**原片**：视频的 src 是压缩流(/video)，预热时还原为原片
   * (/content)——原片进缓存后，任何网络下都可下载/回看完整版。
   */
  useEffect(() => {
    const raw = current?.originalSrc ?? current?.src;
    if (!raw) return;
    const m = raw.match(/\/api\/media\/cache\?url=([^&]+)/);
    let original = m ? decodeURIComponent(m[1]) : raw;
    // 压缩流 → 原片（/photo/{id}/video → /photo/{id}/content）
    original = original.replace(/(\/photo\/\d+\/)video(\?|$)/, "$1content$2");
    if (!/^https?:\/\//i.test(original)) return;
    requestJson<{ cached: boolean; allowed: boolean }>("/media/warm", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: original }),
    }).catch(() => {
      // 预热失败静默（观看本身不受影响）
    });
  }, [current?.src, current?.originalSrc]);

  /**
   * 预加载相邻大图（左右各一张）：手机相册的原图要走中继隧道，
   * 实测单张 ~0.7s——等按下方向键再开始下载，用户会盯着空白等。
   * 提前把邻居塞进浏览器缓存，切换时几乎瞬时。
   *
   * 用 <link rel=preload> 而非 new Image()：前者不占额外解码内存，
   * 加载完可由 <img> 直接命中缓存。视频邻居不预载（体积大，按需流式）。
   */
  useEffect(() => {
    if (!hasMultiple) return;
    const links: HTMLLinkElement[] = [];
    for (const delta of [1, -1]) {
      const neighbor = images[(index + delta + images.length) % images.length];
      const href = neighbor?.src;
      if (!href || href === current?.src || neighbor?.kind === "video") continue;
      const link = document.createElement("link");
      link.rel = "preload";
      link.as = "image";
      link.href = href;
      document.head.appendChild(link);
      links.push(link);
    }
    return () => links.forEach((l) => l.remove());
  }, [images, index, hasMultiple, current?.src]);

  const go = useCallback(
    (delta: number) => {
      if (!hasMultiple) return;
      const next = (index + delta + images.length) % images.length;
      setLoaded(false);
      onIndexChange(next);
    },
    [hasMultiple, images.length, index, onIndexChange],
  );

  // 键盘：Esc 关闭、← → 切换(视频当前项时左右键留给播放器做进度调节)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      } else if (e.key === "ArrowLeft" && !isVideo) {
        e.preventDefault();
        go(-1);
      } else if (e.key === "ArrowRight" && !isVideo) {
        e.preventDefault();
        go(1);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [go, onClose, isVideo]);

  // 打开期间锁定页面滚动
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  if (!current) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={current.caption ?? current.alt ?? "图片预览"}
      className="fixed inset-0 z-50 flex flex-col bg-black/85 backdrop-blur-sm animate-in fade-in"
      onClick={onClose}
    >
      {/* 顶栏：计数 / 说明 + 显式出口（新窗口、下载）与关闭 */}
      <div
        className="flex items-center gap-3 px-4 py-2.5 text-white/90 shrink-0"
        onClick={(e) => e.stopPropagation()}
      >
        <span className="text-xs tabular-nums text-white/60 shrink-0">
          {index + 1} / {images.length}
        </span>
        {(current.caption || current.alt) && (
          <span className="text-xs truncate min-w-0">{current.caption ?? current.alt}</span>
        )}
        <div className="ml-auto flex items-center gap-1 shrink-0">
          <a
            href={current.originalSrc ?? current.src}
            target="_blank"
            rel="noreferrer"
            title="在新窗口打开"
            onClick={(e) => e.stopPropagation()}
            className="p-2 rounded-lg hover:bg-white/10 transition-colors"
          >
            <ExternalLink className="w-4 h-4" />
          </a>
          <a
            href={current.originalSrc ?? current.src}
            download
            title={isVideo ? "下载原片" : "下载原图"}
            onClick={(e) => e.stopPropagation()}
            className="p-2 rounded-lg hover:bg-white/10 transition-colors"
          >
            <Download className="w-4 h-4" />
          </a>
          <button
            type="button"
            title="关闭（Esc）"
            onClick={onClose}
            className="p-2 rounded-lg hover:bg-white/10 transition-colors cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 媒体区：点击遮罩关闭、点击媒体本身不关闭 */}
      <div className="flex-1 min-h-0 flex items-center justify-center px-4 pb-6 relative">
        {hasMultiple && (
          <button
            type="button"
            aria-label="上一个"
            onClick={(e) => {
              e.stopPropagation();
              go(-1);
            }}
            className="absolute left-3 top-1/2 -translate-y-1/2 p-2.5 rounded-full bg-black/45 hover:bg-black/65 text-white transition-colors cursor-pointer z-10"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
        )}
        {isVideo ? (
          // 视频:原生播放器(播放/暂停/进度/音量/全屏);点击视频本身不关灯箱
          // 默认静音:自动播放的视频突然出声很唐突(也可能被浏览器自动播放策略
          // 拦截);需要声音时点播放器音量图标自行打开。
          // 注:React 的 muted 属性不可靠(不渲染 DOM attribute),用 ref 直接设。
          <video
            key={current.src}
            ref={(el) => {
              if (el) el.muted = true;
            }}
            src={current.src}
            poster={current.thumb}
            controls
            autoPlay
            playsInline
            muted
            preload="metadata"
            onClick={(e) => e.stopPropagation()}
            onLoadedMetadata={() => setLoaded(true)}
            className="max-w-full max-h-full rounded-lg shadow-2xl bg-black"
          >
            您的浏览器不支持视频播放。
          </video>
        ) : (
          <>
            <img
              key={current.src}
              src={current.src}
              alt={current.alt ?? current.caption ?? "图片"}
              onLoad={() => setLoaded(true)}
              onClick={(e) => e.stopPropagation()}
              className="max-w-full max-h-full object-contain rounded-lg shadow-2xl"
            />
            {/* 大图未加载完时，先用缩略图铺底（避免白屏等待） */}
            {!loaded && current.thumb && current.thumb !== current.src && (
              <img
                src={current.thumb}
                alt=""
                aria-hidden
                className="absolute max-w-full max-h-full object-contain rounded-lg shadow-2xl blur-sm"
              />
            )}
          </>
        )}
        {hasMultiple && (
          <button
            type="button"
            aria-label="下一个"
            onClick={(e) => {
              e.stopPropagation();
              go(1);
            }}
            className="absolute right-3 top-1/2 -translate-y-1/2 p-2.5 rounded-full bg-black/45 hover:bg-black/65 text-white transition-colors cursor-pointer z-10"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        )}
      </div>
    </div>
  );
}
