import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Download, ExternalLink, FileText, Maximize2, Minimize2, X } from "lucide-react";
import { FileViewerStatus } from "@/hooks/useFileViewer";
import { FileItem, FilePreview } from "@/types";
import { filesApi } from "@/lib/services/filesApi";
import { USE_BACKEND } from "@/lib/api/client";
import { toast } from "sonner";
import { PdfPreview } from "./preview/PdfPreview";
import { WordPreview } from "./preview/WordPreview";
import { ExcelPreview } from "./preview/ExcelPreview";
import { ImagePreview } from "./preview/ImagePreview";
import { VideoPreview, AudioPreview } from "./preview/VideoPreview";
import { TextPreview } from "./preview/TextPreview";
import { Unsupported } from "./preview/Unsupported";
import { PreviewSkeleton } from "./preview/PreviewSkeleton";

interface FileViewerModalProps {
  file: FileItem | null;
  preview: FilePreview | null;
  status: FileViewerStatus;
  onClose: () => void;
  /** 在列表内切换（←/→ 键与箭头按钮；不传则隐藏导航）。 */
  onNavigate?: (delta: number) => void;
  hasPrev?: boolean;
  hasNext?: boolean;
  /** 列表位置（"3 / 12"）。 */
  position?: { index: number; total: number } | null;
}

/**
 * 文件预览器（2026-09-17 整体升级）。
 *
 * 高级/简约：自绘全屏覆盖层（不套通用 Modal）——沉浸式深色背景、
 * 玻璃质感工具条、内容区最大化；不再有嵌套边框的笨重感。
 *
 * 灵动：←/→ 键盘与悬浮箭头切换文件（列表内）、Esc 关闭、双击内容区
 * 全屏切换；切换时淡入过渡。上下文件由查看器 hook 预取，切换即秒开。
 *
 * 智能：按内容类型自动选渲染器与宽度（PDF/表格全宽、文本适中）；
 * 头部只留文件名 + 类型/大小/位置，动作收纳为图标按钮（hover 提示）。
 */
export function FileViewerModal({ file, preview, status, onClose, onNavigate, hasPrev, hasNext, position }: FileViewerModalProps) {
  const [fullscreen, setFullscreen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  /** 下载真实生效:后端模式打开下载端点(单文件直出原文件)。 */
  const handleDownload = useCallback(() => {
    if (!file) return;
    if (!USE_BACKEND) {
      toast.info("下载需要连接后端服务");
      return;
    }
    window.open(filesApi.downloadUrl([file.id]), "_blank");
  }, [file]);

  /** 新窗口打开:raw 字节直连标签页(浏览器渲染 PDF/图片/视频)。 */
  const handleOpenExternal = useCallback(() => {
    if (!file || !USE_BACKEND) return;
    window.open(`/api/files/${file.id}/raw`, "_blank");
  }, [file]);

  // 键盘:Esc 关闭、←/→ 切换、F 全屏(输入框聚焦时不拦截)。
  // 内层灯箱(ImageLightbox)打开时它自己处理键盘——通过 body 上的标记检测,
  // 避免一次 Esc 连关两层 / ←→ 把文件切走(实测冲突)。
  useEffect(() => {
    if (!file) return;
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA") return;
      // 内层灯箱打开:键盘归它管
      if (document.body.dataset.noraLightboxOpen === "1") return;
      if (e.key === "Escape") {
        e.preventDefault();
        if (fullscreen) setFullscreen(false);
        else onClose();
      } else if (e.key === "ArrowLeft" && onNavigate) {
        e.preventDefault();
        onNavigate(-1);
      } else if (e.key === "ArrowRight" && onNavigate) {
        e.preventDefault();
        onNavigate(1);
      } else if ((e.key === "f" || e.key === "F") && !e.ctrlKey && !e.metaKey && !e.altKey) {
        // 排除修饰键:Ctrl+F 是浏览器查找,不该切全屏
        setFullscreen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [file, fullscreen, onClose, onNavigate]);

  // 打开期间锁定页面滚动
  useEffect(() => {
    if (!file) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [file]);

  // 切换文件时退出全屏(不同内容类型适配不同宽度)
  useEffect(() => {
    setFullscreen(false);
  }, [file?.id]);

  if (!file) return null;

  const Icon = file.icon;

  const renderBody = () => {
    if (status === "loading") return <PreviewSkeleton />;
    if (status === "error") return <Unsupported fileName={file.name} fileId={file.id} onDownload={handleDownload} />;
    switch (preview?.kind) {
      case "pdf": return <PdfPreview preview={preview} />;
      case "word": return <WordPreview preview={preview} />;
      case "excel": return <ExcelPreview preview={preview} />;
      case "image": return <ImagePreview preview={preview} />;
      case "video": return <VideoPreview preview={preview} />;
      case "audio": return <AudioPreview preview={preview} />;
      case "text": return <TextPreview preview={preview} />;
      default: return <Unsupported fileName={file.name} fileId={file.id} onDownload={handleDownload} />;
    }
  };

  // 内容适配:媒体(图片/视频)无边界全高展示;PDF/表格全宽;
  // 文本/Word 适中宽度(阅读体验)
  const isMedia = preview?.kind === "image" || preview?.kind === "video";
  const wide = preview?.kind === "pdf" || preview?.kind === "excel" || isMedia;
  const bodyWidth = fullscreen || wide ? "max-w-none w-full" : "max-w-[860px] mx-auto w-full";

  return (
    <div
      ref={containerRef}
      role="dialog"
      aria-modal="true"
      aria-label={file.name}
      className="fixed inset-0 z-50 flex flex-col bg-black/85 backdrop-blur-md animate-in fade-in duration-150"
      onClick={onClose}
    >
      {/* 顶部工具条:玻璃质感 + 极简(信息在左,动作图标在右) */}
      <div
        className="shrink-0 flex items-center gap-3 px-4 py-2.5"
        onClick={(e) => e.stopPropagation()}
      >
        <Icon className="w-4 h-4 shrink-0 text-white/70" />
        <div className="min-w-0 flex items-baseline gap-2">
          <span className="text-sm font-medium text-white truncate max-w-[40vw]">{file.name}</span>
          <span className="text-[10px] text-white/50 shrink-0 hidden sm:inline">
            {file.type} · {file.size}
            {position ? ` · ${position.index}/${position.total}` : ""}
          </span>
        </div>

        <div className="ml-auto flex items-center gap-0.5 shrink-0">
          {onNavigate && (
            <>
              <button
                type="button"
                title="上一个（←）"
                disabled={!hasPrev}
                onClick={() => onNavigate(-1)}
                className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-default"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                type="button"
                title="下一个（→）"
                disabled={!hasNext}
                onClick={() => onNavigate(1)}
                className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-default"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
              <div className="w-px h-4 bg-white/15 mx-1" />
            </>
          )}
          {USE_BACKEND && (
            <button
              type="button"
              title="新窗口打开"
              onClick={handleOpenExternal}
              className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer"
            >
              <ExternalLink className="w-4 h-4" />
            </button>
          )}
          <button
            type="button"
            title="下载"
            onClick={handleDownload}
            className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer"
          >
            <Download className="w-4 h-4" />
          </button>
          <button
            type="button"
            title={fullscreen ? "退出全屏（F）" : "全屏（F）"}
            onClick={() => setFullscreen((v) => !v)}
            className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer"
          >
            {fullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
          </button>
          <button
            type="button"
            title="关闭（Esc）"
            onClick={onClose}
            className="p-2 rounded-lg text-white/70 hover:text-white hover:bg-white/10 transition-colors cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 内容区:点击空白关闭,双击内容全屏;切换文件淡入。
          媒体(图片/视频)撑满全高——无边界原比例;其余类型正常滚动。 */}
      <div
        className={`flex-1 min-h-0 px-3 sm:px-6 ${isMedia ? "pb-3 overflow-hidden" : "pb-6 overflow-y-auto custom-scroll"}`}
        onClick={onClose}
      >
        <div
          className={`${bodyWidth} ${isMedia || fullscreen ? "h-full" : ""} transition-all duration-200`}
          onClick={(e) => e.stopPropagation()}
          // 双击全屏只在媒体类内容上生效——文本/Word/表格里双击是"选中词",
          // 全局挂会打断阅读(选中一个词的同时布局跳到全屏)
          onDoubleClick={isMedia ? () => setFullscreen((v) => !v) : undefined}
        >
          <div key={file.id} className={`animate-in fade-in slide-in-from-bottom-1 duration-200 ${isMedia ? "h-full" : ""}`}>
            {renderBody()}
          </div>
        </div>
      </div>

      {/* 底部悬浮提示(角落小字,不占布局) */}
      <div className="shrink-0 pb-1.5 text-center text-[10px] text-white/30 select-none pointer-events-none hidden sm:block">
        {onNavigate ? "← → 切换文件 · " : ""}双击全屏 · Esc 关闭
      </div>
    </div>
  );
}
