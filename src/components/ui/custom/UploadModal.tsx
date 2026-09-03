'use client';

import { CloudUpload, CheckCircle2 } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { useSimulatedUpload } from "@/hooks/useUpload";

type UploadInstance = ReturnType<typeof useSimulatedUpload>;

interface UploadModalProps {
  upload: UploadInstance;
  title: string;
  hint?: string;
  onUploadComplete?: () => void;
}

/**
 * 全局统一的上传弹窗：所有页面的上传交互共用同一状态机（useSimulatedUpload）。
 */
export function UploadModal({ upload, title, hint = "单文件最大支持 50MB", onUploadComplete }: UploadModalProps) {
  return (
    <Modal isOpen={upload.isOpen} onClose={upload.close} title={title} width="w-[90%] sm:w-[450px]">
      {upload.status === "idle" && (
        <div
          onClick={() => upload.startUpload(onUploadComplete)}
          onDragOver={upload.handleDragOver}
          onDragLeave={upload.handleDragLeave}
          onDrop={(e) => upload.handleDrop(e, onUploadComplete)}
          className={`border-2 border-dashed rounded-xl p-8 sm:p-10 flex flex-col items-center justify-center text-center transition-all cursor-pointer group ${
            upload.isDragging
              ? "border-blue-500 bg-blue-100 scale-[1.02]"
              : "border-blue-200 bg-blue-50/30 hover:bg-blue-50"
          }`}
        >
          <div className={`w-12 h-12 bg-white shadow-sm rounded-full flex items-center justify-center mb-4 transition-transform ${upload.isDragging ? "scale-125" : "group-hover:scale-110"}`}>
            <CloudUpload className="w-6 h-6 text-blue-500" />
          </div>
          <div className="text-sm font-bold text-gray-800">
            {upload.isDragging ? "松开鼠标以开始上传" : "点击或拖拽文件到此处"}
          </div>
          <div className="text-xs text-gray-400 mt-1">{hint}</div>
        </div>
      )}
      {upload.status === "uploading" && (
        <div className="py-12 flex flex-col items-center justify-center">
          <div className="w-12 h-12 border-4 border-blue-100 border-t-blue-500 rounded-full animate-spin mb-4"></div>
          <div className="text-sm font-bold text-gray-800">正在上传...</div>
          <div className="text-xs text-gray-400 mt-1">处理完毕后将自动添加到列表</div>
        </div>
      )}
      {upload.status === "success" && (
        <div className="py-12 flex flex-col items-center justify-center animate-in zoom-in">
          <CheckCircle2 className="w-12 h-12 text-green-500 mb-4" />
          <div className="text-sm font-bold text-gray-800">上传成功</div>
          <div className="text-xs text-gray-400 mt-1">文件已保存到您的空间</div>
        </div>
      )}
    </Modal>
  );
}
