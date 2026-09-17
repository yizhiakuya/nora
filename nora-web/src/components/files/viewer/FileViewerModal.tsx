import { Clock, Download, ExternalLink } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
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
}

/**
 * 文件预览弹窗(2026-09-17 打磨)。
 *
 * - 下载真实生效(此前是 toast 提示):后端模式走 /api/files/download 端点
 *   (单文件出原文件);mock 模式提示。
 * - 「新窗口打开」:raw 端点直连浏览器标签页(适合全屏看 PDF/视频)。
 * - 移除了假的「收藏」按钮(无后端支持,不做假交互)。
 * - 宽屏自适应:PDF/表格类预览用更宽弹窗(94vw 上限 1000px)。
 */
export function FileViewerModal({ file, preview, status, onClose }: FileViewerModalProps) {
  if (!file) return null;

  const Icon = file.icon;

  /** 下载真实生效:后端模式打开下载端点(单文件直出原文件)。 */
  const handleDownload = () => {
    if (!USE_BACKEND) {
      toast.info("下载需要连接后端服务");
      return;
    }
    window.open(filesApi.downloadUrl([file.id]), "_blank");
  };

  /** 新窗口打开:raw 字节直连标签页(浏览器渲染 PDF/图片/视频)。 */
  const handleOpenExternal = () => {
    if (!USE_BACKEND) return;
    window.open(`/api/files/${file.id}/raw`, "_blank");
  };

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

  // PDF/表格用更宽弹窗(内容密度高);文本/图片保持标准宽
  const wide = preview?.kind === "pdf" || preview?.kind === "excel";

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={file.name}
      width={wide ? "w-[96%] sm:w-[1000px]" : "w-[94%] sm:w-[720px]"}
    >
      <div className="flex items-center justify-between gap-3 flex-wrap mb-4 pb-3 border-b border-border">
        <div className="flex items-center gap-3 min-w-0">
          <Icon className={`${file.color} w-5 h-5 shrink-0`} />
          <div className="min-w-0">
            <div className="text-sm font-bold text-foreground truncate">{file.name}</div>
            <div className="text-[10px] text-muted-foreground flex items-center gap-2 mt-0.5">
              <span>{file.type}</span><span>·</span><span>{file.size}</span>
              <span>·</span><span className="flex items-center gap-1"><Clock className="w-3 h-3" /> {file.date}</span>
              {file.indexed && (
                <>
                  <span>·</span>
                  <span className="text-green-600 dark:text-green-400">已入知识库</span>
                </>
              )}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          {USE_BACKEND && (
            <Button variant="outline" size="sm" className="h-7 text-xs bg-card" onClick={handleOpenExternal} title="在浏览器标签页打开（适合全屏查看）">
              <ExternalLink className="w-3.5 h-3.5 mr-1" /> 新窗口
            </Button>
          )}
          <Button variant="outline" size="sm" className="h-7 text-xs bg-card" onClick={handleDownload}>
            <Download className="w-3.5 h-3.5 mr-1" /> 下载
          </Button>
        </div>
      </div>
      <div className="max-h-[68vh] overflow-y-auto custom-scroll p-1">{renderBody()}</div>
    </Modal>
  );
}
