import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, TouchEvent as ReactTouchEvent } from "react";
import { ChevronLeft, ChevronRight, Download, ExternalLink, X, ZoomIn, ZoomOut } from "lucide-react";
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
 * 图片缩放/平移（2026-09-18，全局统一）：
 * - 滚轮缩放（以光标为锚点）、双击在 1x/2.5x 间切换、工具条 ± 按钮；
 * - 放大后按住拖拽平移（grabbing 光标）；缩小/切换图片时自动复位；
 * - 触屏双指捏合（pointer events 双指距离比）。
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

  // ---------- 图片缩放/平移(2026-09-18,全局统一) ----------
  const containerRef = useRef<HTMLDivElement | null>(null);
  /** 缩放倍率(1 = 适应屏幕;0.5-5)。切换图片/关闭时复位。 */
  const [scale, setScale] = useState(1);
  /** 平移偏移(px;仅放大后拖拽产生)。 */
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const dragging = useRef<{ startX: number; startY: number; baseX: number; baseY: number } | null>(null);
  /** 触屏双指捏合:两指距离与基准倍率。 */
  const pinch = useRef<{ dist: number; baseScale: number } | null>(null);

  /** 适配尺寸(px):由自然尺寸与容器尺寸算出「撑满可用空间」的显示大小。 */
  const [fit, setFit] = useState<{ w: number; h: number } | null>(null);

  const resetView = useCallback(() => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  }, []);

  /** 按容器可用空间计算等比适配尺寸(含放大:小图也撑到合适大小)。 */
  const recomputeFit = useCallback((el: HTMLImageElement) => {
    const c = containerRef.current;
    if (!c || !el.naturalWidth || !el.naturalHeight) return;
    // 容器内边距:px-4(左右各 16) + pb-6(下 24)
    const cw = Math.max(80, c.clientWidth - 32);
    const ch = Math.max(80, c.clientHeight - 24);
    const s = Math.min(cw / el.naturalWidth, ch / el.naturalHeight);
    setFit({ w: Math.round(el.naturalWidth * s), h: Math.round(el.naturalHeight * s) });
  }, []);

  /**
   * 加载后重算(等布局稳定)。
   *
   * 为什么延迟:onLoad 触发时,fit 尚未设置 → img 还是 natural 尺寸或 0,
   * 容器 flex 布局可能还在中间态(实测踩过:1280x2772 的图只算出 405x420,
   * 应撑满 ~878 高)。rAF + setTimeout(0) 双保险让浏览器完成一轮布局后再测。
   */
  const recomputeFitSoon = useCallback((el: HTMLImageElement) => {
    requestAnimationFrame(() => {
      recomputeFit(el);
      // 再补一帧(容器含异步内容时首帧可能仍不稳)
      setTimeout(() => recomputeFit(el), 50);
    });
  }, [recomputeFit]);

  // 窗口/容器尺寸变化时重算适配(特性检测:jsdom 测试环境无 ResizeObserver)
  useEffect(() => {
    const c = containerRef.current;
    if (!c || isVideo || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      const img = c.querySelector("img[data-main]") as HTMLImageElement | null;
      if (img && img.complete) recomputeFit(img);
    });
    ro.observe(c);
    return () => ro.disconnect();
  }, [isVideo, recomputeFit]);

  // 切换图片时复位缩放/平移
  useEffect(() => {
    resetView();
  }, [index, resetView]);

  /** 以锚点(容器内坐标)为中心缩放:保持锚点内容不移动(对齐常见地图/图片预览)。 */
  const zoomAt = useCallback((nextScale: number, anchorX?: number, anchorY?: number) => {
    setScale((prev) => {
      const clamped = Math.max(0.5, Math.min(5, +nextScale.toFixed(2)));
      if (anchorX != null && anchorY != null) {
        // 锚点补偿:缩放中心在 (anchorX, anchorY) 时,内容位移 = (1 - next/prev) * (anchor - center - offset)
        const rect = containerRef.current?.getBoundingClientRect();
        if (rect) {
          const cx = anchorX - rect.width / 2 - rect.left;
          const cy = anchorY - rect.height / 2 - rect.top;
          setOffset((o) => ({
            x: o.x + cx * (1 - clamped / prev),
            y: o.y + cy * (1 - clamped / prev),
          }));
        }
      }
      return clamped;
    });
  }, []);

  /** 滚轮缩放(图片时;视频交给播放器)。 */
  const onWheel = useCallback((e: WheelEvent) => {
    if (isVideo) return;
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    zoomAt(scale * factor, e.clientX, e.clientY);
  }, [isVideo, scale, zoomAt]);

  /**
   * 原生 wheel/touchmove 监听(passive:false,2026-09-19 修复)。
   *
   * React 17+ 在根节点以 passive 注册 onWheel/onTouchMove——其中调用
   * preventDefault 无效,控制台报 "Unable to preventDefault inside passive
   * event listener invocation";后果:滚轮缩放时页面同时滚动、触屏捏合时
   * 浏览器自身页面缩放与自定义捏合打架。必须用 addEventListener
   * {passive:false} 原生挂载。
   *
   * 用 ref 转发最新 handler:监听器只挂一次,回调始终读当前渲染的
   * isVideo/scale/zoomAt(避免反复解绑重绑)。ref 在 effect 里更新
   * (React 约定:渲染期不写 ref)。
   */
  const wheelRef = useRef(onWheel);
  useEffect(() => {
    wheelRef.current = onWheel;
  }, [onWheel]);
  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    const handler = (e: WheelEvent) => wheelRef.current(e);
    c.addEventListener("wheel", handler, { passive: false });
    return () => c.removeEventListener("wheel", handler);
  }, []);

  /** 双击:1x ⇄ 2.5x(以点击点为锚)。 */
  const onDoubleClick = useCallback((e: ReactMouseEvent) => {
    if (isVideo) return;
    e.stopPropagation();
    if (scale > 1.01) {
      resetView();
    } else {
      zoomAt(2.5, e.clientX, e.clientY);
    }
  }, [isVideo, scale, resetView, zoomAt]);

  /** 拖拽平移(放大后):pointer 事件统一鼠标/触屏。 */
  const onPointerDown = useCallback((e: ReactPointerEvent) => {
    if (isVideo || scale <= 1.01) return;
    e.stopPropagation();
    dragging.current = { startX: e.clientX, startY: e.clientY, baseX: offset.x, baseY: offset.y };
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
  }, [isVideo, scale, offset]);

  const onPointerMove = useCallback((e: ReactPointerEvent) => {
    if (!dragging.current) return;
    setOffset({
      x: dragging.current.baseX + (e.clientX - dragging.current.startX),
      y: dragging.current.baseY + (e.clientY - dragging.current.startY),
    });
  }, []);

  const onPointerUp = useCallback(() => {
    dragging.current = null;
  }, []);

  /** 触屏双指捏合。 */
  const onTouchStart = useCallback((e: ReactTouchEvent) => {
    if (isVideo || e.touches.length !== 2) return;
    const dx = e.touches[0].clientX - e.touches[1].clientX;
    const dy = e.touches[0].clientY - e.touches[1].clientY;
    pinch.current = { dist: Math.hypot(dx, dy), baseScale: scale };
  }, [isVideo, scale]);

  const onTouchMove = useCallback((e: TouchEvent) => {
    if (!pinch.current || e.touches.length !== 2) return;
    e.preventDefault();
    const dx = e.touches[0].clientX - e.touches[1].clientX;
    const dy = e.touches[0].clientY - e.touches[1].clientY;
    const ratio = Math.hypot(dx, dy) / pinch.current.dist;
    zoomAt(pinch.current.baseScale * ratio);
  }, [zoomAt]);

  const onTouchEnd = useCallback(() => {
    pinch.current = null;
  }, []);

  // touchmove 同样用原生 {passive:false}(见 onWheel 注释:React 的
  // onTouchMove 是 passive,preventDefault 无效,浏览器页面缩放会打架)
  const touchMoveRef = useRef(onTouchMove);
  useEffect(() => {
    touchMoveRef.current = onTouchMove;
  }, [onTouchMove]);
  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    const handler = (e: TouchEvent) => touchMoveRef.current(e);
    c.addEventListener("touchmove", handler, { passive: false });
    return () => c.removeEventListener("touchmove", handler);
  }, []);

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
   *
   * 延迟 600ms + 去重（2026-09-19 审查修复）：快速开关灯箱不该触发预载
   * （预载了没人用 → Chrome 控制台 preload 警告刷屏，实测 129 条）；
   * 两张图的画廊两个方向是同一张，去重避免重复 <link>。
   */
  useEffect(() => {
    if (!hasMultiple) return;
    const links: HTMLLinkElement[] = [];
    const timer = setTimeout(() => {
      const seen = new Set<string>();
      for (const delta of [1, -1]) {
        const neighbor = images[(index + delta + images.length) % images.length];
        const href = neighbor?.src;
        if (!href || href === current?.src || neighbor?.kind === "video" || seen.has(href)) continue;
        seen.add(href);
        const link = document.createElement("link");
        link.rel = "preload";
        link.as = "image";
        link.href = href;
        document.head.appendChild(link);
        links.push(link);
      }
    }, 600);
    return () => {
      clearTimeout(timer);
      links.forEach((l) => l.remove());
    };
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

  // 打开期间锁定页面滚动 + 标记灯箱已打开(外层查看器据此让出键盘控制权,
  // 避免一次 Esc 连关两层/←→ 把文件切走)
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.body.dataset.noraLightboxOpen = "1";
    return () => {
      document.body.style.overflow = prev;
      delete document.body.dataset.noraLightboxOpen;
    };
  }, []);

  if (!current) return null;

  // 挂到 document.body:灯箱是全局覆盖层,必须脱离聊天内容的样式域——
  // 此前嵌在 .chat-markdown 子树里,被其 img 规则(420px 限高/白边框/圆角)
  // 命中,全屏图显示不全、周围一圈白框(实测踩过)。
  return createPortal(
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

      {/* 媒体区：点击遮罩关闭、点击媒体本身不关闭；图片支持滚轮/双击/捏合缩放与拖拽平移。
          wheel/touchmove 走原生 {passive:false} 监听(见 onWheel 注释),不在此绑定 */}
      <div
        ref={containerRef}
        className="flex-1 min-h-0 flex items-center justify-center px-4 pb-6 relative overflow-hidden"
        onDoubleClick={onDoubleClick}
      >
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
            className="max-w-full max-h-full bg-black"
          >
            您的浏览器不支持视频播放。
          </video>
        ) : (
          <>
            <img
              key={current.src}
              data-main="1"
              src={current.src}
              alt={current.alt ?? current.caption ?? "图片"}
              onLoad={(e) => {
                setLoaded(true);
                recomputeFitSoon(e.currentTarget);
              }}
              onClick={(e) => e.stopPropagation()}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerUp}
              onTouchStart={onTouchStart}
              onTouchEnd={onTouchEnd}
              draggable={false}
              style={{
                width: fit ? `${fit.w}px` : undefined,
                height: fit ? `${fit.h}px` : undefined,
                transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
                transition: dragging.current ? "none" : "transform 0.15s ease-out",
              }}
              className={`max-w-full max-h-full object-contain select-none ${
                scale > 1.01 ? "cursor-grab active:cursor-grabbing" : "cursor-zoom-in"
              }`}
            />
            {/* 大图未加载完时，先用缩略图铺底（避免白屏等待）；加载完成即移除 */}
            {!loaded && current.thumb && current.thumb !== current.src && (
              <img
                src={current.thumb}
                alt=""
                aria-hidden
                className="absolute max-w-full max-h-full object-contain blur-sm"
              />
            )}
            {/* 缩放工具条(图片时显示;悬浮右下角,不占布局) */}
            <div
              className="absolute bottom-4 right-4 flex items-center gap-0.5 bg-black/55 backdrop-blur-md rounded-xl px-1.5 py-1 shadow-lg z-10"
              onClick={(e) => e.stopPropagation()}
            >
              <span className="text-[10px] text-white/60 tabular-nums px-1.5 select-none">
                {Math.round(scale * 100)}%
              </span>
              <button
                type="button"
                title="缩小（滚轮/双击也可）"
                onClick={() => zoomAt(scale / 1.25)}
                disabled={scale <= 0.5}
                className="p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer disabled:opacity-30"
              >
                <ZoomOut className="w-3.5 h-3.5" />
              </button>
              <button
                type="button"
                title="放大（滚轮/双击也可）"
                onClick={() => zoomAt(scale * 1.25)}
                disabled={scale >= 5}
                className="p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer disabled:opacity-30"
              >
                <ZoomIn className="w-3.5 h-3.5" />
              </button>
              {scale > 1.01 && (
                <button
                  type="button"
                  title="恢复原始大小"
                  onClick={resetView}
                  className="px-1.5 py-1 rounded-lg text-[10px] text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer"
                >
                  1:1
                </button>
              )}
            </div>
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
    </div>,
    document.body,
  );
}
