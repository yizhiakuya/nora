import { useCallback, useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, Download, ExternalLink, X } from "lucide-react";

/**
 * 图片灯箱：聊天里的照片点击后页内放大查看，不再跳外部标签页。
 *
 * 交互（对齐常见图片预览）：
 * - 点击遮罩 / 右上角 × / Esc 关闭；
 * - 多张时左右箭头 / ← → 键切换（单张时隐藏）；
 * - 顶栏给"新窗口打开"与"下载"两个显式出口（需要时才离开页内）。
 */

export interface LightboxImage {
  /** 大图（原图）地址 */
  src: string;
  /** 列表缩略图（先用它做即时占位，大图加载完再替换，避免白屏） */
  thumb?: string;
  /** 说明文字（agent 填写的 caption 等） */
  caption?: string;
  alt?: string;
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

  const go = useCallback(
    (delta: number) => {
      if (!hasMultiple) return;
      const next = (index + delta + images.length) % images.length;
      setLoaded(false);
      onIndexChange(next);
    },
    [hasMultiple, images.length, index, onIndexChange],
  );

  // 键盘：Esc 关闭、← → 切换
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        go(-1);
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        go(1);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [go, onClose]);

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
            href={current.src}
            target="_blank"
            rel="noreferrer"
            title="在新窗口打开"
            onClick={(e) => e.stopPropagation()}
            className="p-2 rounded-lg hover:bg-white/10 transition-colors"
          >
            <ExternalLink className="w-4 h-4" />
          </a>
          <a
            href={current.src}
            download
            title="下载原图"
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

      {/* 图片区：点击遮罩关闭、点击图片本身不关闭 */}
      <div className="flex-1 min-h-0 flex items-center justify-center px-4 pb-6 relative">
        {hasMultiple && (
          <button
            type="button"
            aria-label="上一张"
            onClick={(e) => {
              e.stopPropagation();
              go(-1);
            }}
            className="absolute left-3 top-1/2 -translate-y-1/2 p-2.5 rounded-full bg-black/45 hover:bg-black/65 text-white transition-colors cursor-pointer"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
        )}
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
        {hasMultiple && (
          <button
            type="button"
            aria-label="下一张"
            onClick={(e) => {
              e.stopPropagation();
              go(1);
            }}
            className="absolute right-3 top-1/2 -translate-y-1/2 p-2.5 rounded-full bg-black/45 hover:bg-black/65 text-white transition-colors cursor-pointer"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        )}
      </div>
    </div>
  );
}
