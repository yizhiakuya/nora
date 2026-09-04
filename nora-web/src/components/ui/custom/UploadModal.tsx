'use client';

import { CloudUpload, CheckCircle2 } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { useSimulatedUpload } from "@/hooks/useUpload";

type UploadInstance = ReturnType<typeof useSimulatedUpload>;

interface UploadModalProps {
  upload: UploadInstance;
  title: string;
  hint?: string;
  onUploadComplete?: (fileName?: string, file?: import("@/types").FileItem) => void;
}

/**
 * 全局统一的上传弹窗：所有页面的上传交互共用同一状态机（useSimulatedUpload）。
 */
export function UploadModal({ upload, title, hint = "单文件最大支持 50MB", onUploadComplete }: UploadModalProps) {
  return (
    <Modal isOpen={upload.isOpen} onClose={upload.close} title={title} width="w-[90%] sm:w-[450px]">
      {upload.status === "idle" && (
        <div
          onClick={() => upload.startUpload((fileName, file) => onUploadComplete?.(fileName, file))}
          onDragOver={upload.handleDragOver}
          onDragLeave={upload.handleDragLeave}
          onDrop={(e) => upload.handleDrop(e, (fileName, file) => onUploadComplete?.(fileName, file))}
          className={`border-2 border-dashed rounded-xl p-8 sm:p-10 flex flex-col items-center justify-center text-center transition-all cursor-pointer group ${
            upload.isDragging
              ? "border-blue-500 bg-blue-100 dark:bg-blue-900/50 scale-[1.02]"
              : "border-blue-200 dark:border-blue-800 bg-blue-50/30 dark:bg-blue-950/20 hover:bg-blue-50 dark:hover:bg-blue-950/40"
          }`}
        >
          <div className={`w-12 h-12 bg-card shadow-sm rounded-full flex items-center justify-center mb-4 transition-transform ${upload.isDragging ? "scale-125" : "group-hover:scale-110"}`}>
            <CloudUpload className="w-6 h-6 text-blue-500 dark:text-blue-400" />
          </div>
          <div className="text-sm font-bold text-foreground">
            {upload.isDragging ? "松开鼠标以开始上传" : "点击或拖拽文件到此处"}
          </div>
          <div className="text-xs text-muted-foreground mt-1">{hint}</div>
        </div>
      )}
      {upload.status === "uploading" && (
        <div className="py-12 flex flex-col items-center justify-center">
          <div className="w-12 h-12 border-4 border-blue-100 dark:border-blue-900 border-t-blue-500 rounded-full animate-spin mb-4"></div>
          <div className="text-sm font-bold text-foreground">正在上传...</div>
          <div className="text-xs text-muted-foreground mt-1">处理完毕后将自动添加到列表</div>
        </div>
      )}
      {upload.status === "success" && (
        <div className="py-12 flex flex-col items-center justify-center animate-in zoom-in">
          <CheckCircle2 className="w-12 h-12 text-green-500 dark:text-green-400 mb-4" />
          <div className="text-sm font-bold text-foreground">上传成功</div>
          <div className="text-xs text-muted-foreground mt-1">文件已保存到您的空间</div>
        </div>
      )}
    </Modal>
  );
}
