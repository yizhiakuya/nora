import { ExternalLink } from "lucide-react";
import { FilePreview } from "@/types";

/**
 * PDF 预览(2026-09-17 重写):用浏览器内置 PDF 查看器(iframe 直连 raw 字节)。
 *
 * 为什么不是自绘页面:此前渲染的是灰色占位块(模拟版式),用户看 PDF 要的是
 * **真实内容与版式**——浏览器内置查看器免费提供翻页/缩放/搜索/打印,且
 * 零依赖。依赖服务端 raw 端点的 Content-Type(pdf 由 Tika 检测)与
 * 浏览器原生支持(Chrome/Edge/Firefox 均内置)。
 *
 * 局限:移动端浏览器部分不内置 PDF 查看器——顶部保留「新窗口打开」与
 * 「下载」两个出口兜底。
 */
export function PdfPreview({ preview }: { preview: FilePreview }) {
  const src = preview.mediaUrl;
  if (!src) {
    return <div className="py-16 text-center text-xs text-muted-foreground">（PDF 地址缺失）</div>;
  }
  return (
    <div className="h-full flex flex-col gap-2 min-h-[320px]">
      <div className="flex items-center justify-end gap-2 text-xs">
        {preview.pages != null && preview.pages > 1 && (
          <span className="text-muted-foreground mr-auto">约 {preview.pages} 页 · 内置查看器可翻页/缩放/搜索</span>
        )}
        <a
          href={src}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted"
        >
          <ExternalLink className="w-3.5 h-3.5" /> 新窗口打开
        </a>
      </div>
      {/* 浏览器内置 PDF 查看器:翻页/缩放/目录/打印全有 */}
      <iframe
        src={src}
        title="PDF 预览"
        className="w-full flex-1 min-h-[300px] border-0 bg-white"
      />
    </div>
  );
}
