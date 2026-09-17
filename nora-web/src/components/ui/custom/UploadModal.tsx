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
  /** 全部完成后的汇总回调(成功数, 失败数)。 */
  onBatchComplete?: (okCount: number, failCount: number) => void;
}

/**
 * 全局统一的上传弹窗：所有页面的上传交互共用同一状态机（useSimulatedUpload）。
 *
 * 支持多文件：点击区域打开系统多选对话框(隐藏 input)，或一次拖入多个文件；
 * 串行上传并在弹窗内显示进度（第 n/N 个 + 当前文件名）。
 */
export function UploadModal({ upload, title, hint = "单文件最大支持 50MB", onUploadComplete, onBatchComplete }: UploadModalProps) {
  const p = upload.progress;
  return (
    <Modal isOpen={upload.isOpen} onClose={upload.close} title={title} width="w-[90%] sm:w-[450px]">
      {/* 隐藏的多选文件输入(点击区域触发) */}
      <input
        ref={upload.inputRef}
        type="file"
        multiple
        className="hidden"
        aria-hidden
      />
      {upload.status === "idle" && (
        <div
          onClick={() => upload.pickAndUpload(
            (fileName, file) => onUploadComplete?.(fileName, file),
            onBatchComplete,
          )}
          onDragOver={upload.handleDragOver}
          onDragLeave={upload.handleDragLeave}
          onDrop={(e) => upload.handleDrop(
            e,
            (fileName, file) => onUploadComplete?.(fileName, file),
          )}
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
            {upload.isDragging ? "松开鼠标以开始上传" : "点击或拖拽文件到此处（可多选）"}
          </div>
          <div className="text-xs text-muted-foreground mt-1">{hint}</div>
        </div>
      )}
      {upload.status === "uploading" && (
        <div className="py-12 flex flex-col items-center justify-center">
          <div className="w-12 h-12 border-4 border-blue-100 dark:border-blue-900 border-t-blue-500 rounded-full animate-spin mb-4"></div>
          <div className="text-sm font-bold text-foreground">
            {p && p.total > 1 ? `正在上传（${p.done + 1}/${p.total}）` : "正在上传..."}
          </div>
          {p?.current && (
            <div className="text-xs text-muted-foreground mt-1 max-w-[80%] truncate" title={p.current}>
              {p.current}
            </div>
          )}
          {p && p.total > 1 && (
            <div className="w-[70%] h-1.5 bg-muted rounded-full mt-3 overflow-hidden">
              <div
                className="h-full bg-blue-500 transition-all duration-300"
                style={{ width: `${Math.round((p.done / p.total) * 100)}%` }}
              />
            </div>
          )}
          <div className="text-xs text-muted-foreground mt-2">处理完毕后将自动添加到列表</div>
        </div>
      )}
      {upload.status === "error" && (
        <div className="py-12 flex flex-col items-center justify-center animate-in zoom-in">
          <div className="w-12 h-12 rounded-full bg-red-50 dark:bg-red-950/40 flex items-center justify-center mb-4">
            <svg className="w-6 h-6 text-red-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </div>
          <div className="text-sm font-bold text-foreground">上传失败</div>
          <div className="text-xs text-muted-foreground mt-1">
            {upload.progress && upload.progress.total > 1
              ? `${upload.progress.total} 个文件全部失败，请查看错误提示后重试`
              : "请查看错误提示后重试"}
          </div>
        </div>
      )}
      {upload.status === "success" && (
        <div className="py-12 flex flex-col items-center justify-center animate-in zoom-in">
          <CheckCircle2 className="w-12 h-12 text-green-500 dark:text-green-400 mb-4" />
          <div className="text-sm font-bold text-foreground">
            {p && p.total > 1
              ? `上传完成（成功 ${p.done - p.failed}/${p.total}${p.failed > 0 ? `，失败 ${p.failed}` : ""}）`
              : "上传成功"}
          </div>
          <div className="text-xs text-muted-foreground mt-1">文件已保存到您的空间</div>
        </div>
      )}
    </Modal>
  );
}
