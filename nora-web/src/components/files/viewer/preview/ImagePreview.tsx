import { useState } from "react";
import { Maximize2, ZoomIn, ZoomOut } from "lucide-react";
import { ImageLightbox } from "@/components/shared/ImageLightbox";
import { FilePreview } from "@/types";

/**
 * 图片预览(2026-09-17):**无边界沉浸展示**——图片按原始比例填满可用
 * 空间(object-contain 居中),没有方框/边距限制;工具条悬浮在角落,
 * 不占布局。缩放(50%-300%)与全屏灯箱保留。
 */
export function ImagePreview({ preview }: { preview: FilePreview }) {
  const [scale, setScale] = useState(1);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const src = preview.imageUrl;

  if (!src) {
    return <div className="py-16 text-center text-xs text-white/50">（图片地址缺失）</div>;
  }

  return (
    <>
      {/* 无边界:图片直接居中撑满,原比例。放大(>100%)时容器可滚动——
          否则超出部分被 overflow-hidden 裁掉,无法平移查看(只能缩小) */}
      <div className={`relative w-full h-full flex items-center justify-center ${scale > 1 ? "overflow-auto" : "overflow-hidden"}`}>
        <img
          src={src}
          alt="图片预览"
          onClick={() => setLightboxOpen(true)}
          style={{ transform: `scale(${scale})`, transformOrigin: "center" }}
          className="max-w-full max-h-full object-contain cursor-zoom-in transition-transform duration-150 animate-in fade-in"
        />
        {/* 悬浮工具条(不占布局空间) */}
        <div className="absolute bottom-4 right-4 flex items-center gap-0.5 bg-black/55 backdrop-blur-md rounded-xl px-1.5 py-1 shadow-lg">
          <span className="text-[10px] text-white/60 tabular-nums px-1.5 select-none">{Math.round(scale * 100)}%</span>
          <button
            type="button"
            title="缩小"
            onClick={() => setScale((s) => Math.max(0.5, +(s - 0.25).toFixed(2)))}
            disabled={scale <= 0.5}
            className="p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer disabled:opacity-30"
          >
            <ZoomOut className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            title="放大"
            onClick={() => setScale((s) => Math.min(3, +(s + 0.25).toFixed(2)))}
            disabled={scale >= 3}
            className="p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer disabled:opacity-30"
          >
            <ZoomIn className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            title="全屏查看"
            onClick={() => setLightboxOpen(true)}
            className="p-1.5 rounded-lg text-white/70 hover:text-white hover:bg-white/15 transition-colors cursor-pointer"
          >
            <Maximize2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
      {lightboxOpen && (
        <ImageLightbox
          images={[{ src, alt: "图片预览", kind: "image" }]}
          index={0}
          onClose={() => setLightboxOpen(false)}
          onIndexChange={() => { /* 单图无切换 */ }}
        />
      )}
    </>
  );
}
