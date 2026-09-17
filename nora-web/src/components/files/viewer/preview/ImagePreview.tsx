import { useState } from "react";
import { Maximize2, ZoomIn, ZoomOut } from "lucide-react";
import { ImageLightbox } from "@/components/shared/ImageLightbox";
import { FilePreview } from "@/types";

/**
 * 图片预览(2026-09-17 增强):缩放(50%-300%) + 点击进入全屏灯箱。
 *
 * 此前只有静态展示——大图看不清细节,小图浪费空间;缩放按钮覆盖
 * 「看细节」场景,点击图片进灯箱覆盖「沉浸看」场景(灯箱支持
 * 多图切换/下载/新窗口,与聊天里的画廊一致)。
 */
export function ImagePreview({ preview }: { preview: FilePreview }) {
  const [scale, setScale] = useState(1);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const src = preview.imageUrl;

  if (!src) {
    return <div className="py-16 text-center text-xs text-muted-foreground">（图片地址缺失）</div>;
  }

  return (
    <>
      <div className="bg-[#f0f2f5] dark:bg-gray-900 rounded-lg border border-border overflow-hidden">
        {/* 工具条:缩放 + 全屏 */}
        <div className="flex items-center gap-1 px-3 py-1.5 border-b border-border/60 bg-card/50">
          <span className="text-[10px] text-muted-foreground tabular-nums">{Math.round(scale * 100)}%</span>
          <div className="ml-auto flex items-center gap-1">
            <button
              type="button"
              title="缩小"
              onClick={() => setScale((s) => Math.max(0.5, +(s - 0.25).toFixed(2)))}
              disabled={scale <= 0.5}
              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors cursor-pointer disabled:opacity-40"
            >
              <ZoomOut className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              title="放大"
              onClick={() => setScale((s) => Math.min(3, +(s + 0.25).toFixed(2)))}
              disabled={scale >= 3}
              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors cursor-pointer disabled:opacity-40"
            >
              <ZoomIn className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              title="全屏查看"
              onClick={() => setLightboxOpen(true)}
              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
            >
              <Maximize2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
        <div className="p-4 flex items-center justify-center min-h-[340px] max-h-[56vh] overflow-auto custom-scroll">
          <img
            src={src}
            alt="图片预览"
            onClick={() => setLightboxOpen(true)}
            style={{ transform: `scale(${scale})`, transformOrigin: "center" }}
            className="max-w-full max-h-[480px] rounded-lg shadow-md cursor-zoom-in transition-transform duration-150"
          />
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
