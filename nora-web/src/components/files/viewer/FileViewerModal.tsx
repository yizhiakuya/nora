import { Clock, Download, Star } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
import { FileViewerStatus } from "@/hooks/useFileViewer";
import { FileItem, FilePreview } from "@/types";
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

export function FileViewerModal({ file, preview, status, onClose }: FileViewerModalProps) {
  if (!file) return null;

  const Icon = file.icon;

  const renderBody = () => {
    if (status === "loading") return <PreviewSkeleton />;
    if (status === "error") return <Unsupported fileName={file.name} />;
    switch (preview?.kind) {
      case "pdf": return <PdfPreview preview={preview} />;
      case "word": return <WordPreview preview={preview} />;
      case "excel": return <ExcelPreview preview={preview} />;
      case "image": return <ImagePreview preview={preview} />;
      case "video": return <VideoPreview preview={preview} />;
      case "audio": return <AudioPreview preview={preview} />;
      case "text": return <TextPreview preview={preview} />;
      default: return <Unsupported fileName={file.name} />;
    }
  };

  return (
    <Modal isOpen onClose={onClose} title={file.name} width="w-[94%] sm:w-[720px]">
      <div className="flex items-center justify-between gap-3 flex-wrap mb-4 pb-3 border-b border-border">
        <div className="flex items-center gap-3 min-w-0">
          <Icon className={`${file.color} w-5 h-5 shrink-0`} />
          <div className="min-w-0">
            <div className="text-sm font-bold text-foreground truncate">{file.name}</div>
            <div className="text-[10px] text-muted-foreground flex items-center gap-2 mt-0.5">
              <span>{file.type}</span><span>·</span><span>{file.size}</span>
              <span>·</span><span className="flex items-center gap-1"><Clock className="w-3 h-3" /> {file.date}</span>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <Button variant="outline" size="sm" className="h-7 text-xs bg-card" onClick={() => toast.success(`已开始下载 ${file.name}`)}>
            <Download className="w-3.5 h-3.5 mr-1" /> 下载
          </Button>
          <Button variant="outline" size="sm" className="h-7 text-xs bg-card" onClick={() => toast.success("已添加到收藏")}>
            <Star className="w-3.5 h-3.5 mr-1" /> 收藏
          </Button>
        </div>
      </div>
      <div className="max-h-[60vh] overflow-y-auto custom-scroll p-1">{renderBody()}</div>
    </Modal>
  );
}
