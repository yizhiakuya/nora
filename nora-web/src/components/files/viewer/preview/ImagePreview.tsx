import { useState } from "react";
import { RotateCcw, ZoomIn, ZoomOut } from "lucide-react";
import { TransformComponent, TransformWrapper } from "react-zoom-pan-pinch";
import type { FilePreview } from "@/types";

export function ImagePreview({ preview }: { preview: FilePreview }) {
  const [scale, setScale] = useState(1);
  const [failed, setFailed] = useState(false);
  const src = preview.imageUrl;

  if (!src) {
    return <div className="py-16 text-center text-xs text-muted-foreground">（图片地址缺失）</div>;
  }
  if (failed) return <div role="alert" className="p-8 text-sm text-muted-foreground">图片读取失败，请刷新文件或下载查看。</div>;

  return <div className="relative w-full h-full overflow-hidden">
    <TransformWrapper minScale={0.5} maxScale={5} centerOnInit onTransform={(_, state) => setScale(state.scale)}>
      {({ zoomIn, zoomOut, resetTransform }) => <>
        <TransformComponent wrapperStyle={{ width: "100%", height: "100%" }} contentStyle={{ width: "100%", height: "100%", display: "flex", alignItems: "center", justifyContent: "center" }}>
          <img src={src} alt="图片预览" draggable={false} onError={() => setFailed(true)} className="max-w-full max-h-full object-contain select-none" />
        </TransformComponent>
        <div className="absolute bottom-4 right-4 flex items-center gap-0.5 bg-black/55 backdrop-blur-md rounded-xl px-1.5 py-1 shadow-lg text-white">
          <span className="text-[10px] text-white/60 tabular-nums px-1.5 select-none">{Math.round(scale * 100)}%</span>
          <button type="button" aria-label="缩小图片" disabled={scale <= 0.5} onClick={() => zoomOut()} className="p-1.5 rounded-lg hover:bg-white/15 disabled:opacity-30"><ZoomOut className="w-3.5 h-3.5" /></button>
          <button type="button" aria-label="放大图片" disabled={scale >= 5} onClick={() => zoomIn()} className="p-1.5 rounded-lg hover:bg-white/15 disabled:opacity-30"><ZoomIn className="w-3.5 h-3.5" /></button>
          <button type="button" aria-label="重置图片缩放" onClick={() => resetTransform()} className="p-1.5 rounded-lg hover:bg-white/15"><RotateCcw className="w-3.5 h-3.5" /></button>
        </div>
      </>}
    </TransformWrapper>
  </div>;
}
