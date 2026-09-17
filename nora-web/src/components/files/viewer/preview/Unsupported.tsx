import { FileQuestion, Download } from "lucide-react";
import { Button } from "@/components/ui/button";

interface UnsupportedProps {
  fileName: string;
  /** 文件 id(下载用;可选——预览错误态时也有) */
  fileId?: number;
  /** 下载回调(由 FileViewerModal 提供;真实下载) */
  onDownload?: () => void;
}

/**
 * 不支持预览的降级(2026-09-17):下载按钮真实生效(此前是 toast)。
 */
export function Unsupported({ fileName, onDownload }: UnsupportedProps) {
  return (
    <div className="py-16 flex flex-col items-center justify-center text-center">
      <div className="w-16 h-16 bg-muted border border-border rounded-full flex items-center justify-center mb-4">
        <FileQuestion className="w-8 h-8 text-muted-foreground/60" />
      </div>
      <h3 className="text-sm font-bold text-foreground mb-1">该文件类型暂不支持在线预览</h3>
      <p className="text-xs text-muted-foreground mb-5">下载后使用本地应用打开：{fileName}</p>
      {onDownload && (
        <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={onDownload}>
          <Download className="w-3.5 h-3.5 mr-1.5" /> 下载文件
        </Button>
      )}
    </div>
  );
}
