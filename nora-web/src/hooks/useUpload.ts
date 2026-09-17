import { useState, useCallback, useRef, DragEvent } from 'react';
import { toast } from 'sonner';
import { useTimedSequence } from './useTimedSequence';
import { filesApi } from '@/lib/services/filesApi';
import { USE_BACKEND } from '@/lib/api/client';
import { FileItem } from '@/types';

type UploadStatus = 'idle' | 'uploading' | 'success';

/**
 * 上传状态机:USE_BACKEND=true 时真实上传到 file-service,否则本地模拟。
 * 后端模式下 onUploadComplete 收到完整的 FileItem(含服务端 id);
 * Mock 模式保持旧行为(只回传文件名)。
 */
export function useSimulatedUpload(durationMs: number = 2000, successDurationMs: number = 1500) {
  const [isOpen, setIsOpen] = useState(false);
  const [status, setStatus] = useState<UploadStatus>('idle');
  const [isDragging, setIsDragging] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  /** 拖拽上传时的真实文件(后端模式上传它;点击上传无文件保持 null) */
  const fileRef = useRef<globalThis.File | null>(null);
  const fileNameRef = useRef<string | null>(null);
  /** 上传目标文件夹(文件页进入某文件夹时设置;null = 根目录)。 */
  const folderRef = useRef<number | null>(null);

  const open = useCallback(() => {
    setIsOpen(true);
    setStatus('idle');
    setIsDragging(false);
    fileRef.current = null;
    fileNameRef.current = null;
  }, []);

  /** 设置后续上传的目标文件夹(进入文件夹后调用;退出文件夹时传 null)。 */
  const setTargetFolder = useCallback((folderId: number | null) => {
    folderRef.current = folderId;
  }, []);

  const close = useCallback(() => {
    cancelAll();
    setIsOpen(false);
    setIsDragging(false);
  }, [cancelAll]);

  const startUpload = useCallback((onSuccess?: (fileName?: string, file?: FileItem) => void) => {
    if (status !== 'idle') return;
    setStatus('uploading');

    const uploadPromise = USE_BACKEND && fileRef.current
      ? filesApi.uploadFile(fileRef.current, folderRef.current)
          .then((item) => {
            fileNameRef.current = item.name;
            return item;
          })
      : new Promise<FileItem | null>((resolve) => setTimeout(() => resolve(null), durationMs));

    toast.promise(
      uploadPromise,
      {
        loading: '正在上传处理文件...',
        success: (item) => {
          setStatus('success');
          schedule(() => {
            close();
            setStatus('idle');
            if (onSuccess) onSuccess(fileNameRef.current ?? undefined, item ?? undefined);
          }, successDurationMs);
          return '文件上传成功！';
        },
        error: (err: Error) => {
          setStatus('idle');
          return err?.message ? `上传失败：${err.message}` : '文件上传失败';
        },
      }
    );
  }, [status, close, durationMs, successDurationMs, schedule]);

  // Drag & Drop Handlers
  const handleDragOver = useCallback((e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback((e: DragEvent<HTMLDivElement>, onSuccess?: (fileName?: string, file?: FileItem) => void) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      fileRef.current = e.dataTransfer.files[0];
      fileNameRef.current = e.dataTransfer.files[0].name;
      startUpload(onSuccess);
    }
  }, [startUpload]);

  return {
    isOpen,
    status,
    isDragging,
    open,
    close,
    startUpload,
    setTargetFolder,
    handleDragOver,
    handleDragLeave,
    handleDrop
  };
}
