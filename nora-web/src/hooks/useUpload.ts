import { useState, useCallback, useRef, DragEvent } from 'react';
import { toast } from 'sonner';
import { useTimedSequence } from './useTimedSequence';

type UploadStatus = 'idle' | 'uploading' | 'success';

export function useSimulatedUpload(durationMs: number = 2000, successDurationMs: number = 1500) {
  const [isOpen, setIsOpen] = useState(false);
  const [status, setStatus] = useState<UploadStatus>('idle');
  const [isDragging, setIsDragging] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  /** 拖拽上传时的真实文件名（点击上传无文件则保持 null） */
  const fileNameRef = useRef<string | null>(null);

  const open = useCallback(() => {
    setIsOpen(true);
    setStatus('idle');
    setIsDragging(false);
    fileNameRef.current = null;
  }, []);

  const close = useCallback(() => {
    cancelAll();
    setIsOpen(false);
    setIsDragging(false);
  }, [cancelAll]);

  const startUpload = useCallback((onSuccess?: (fileName?: string) => void) => {
    if (status !== 'idle') return;
    setStatus('uploading');
    
    // Simulate real upload promise for Sonner toast
    toast.promise(
      new Promise((resolve) => setTimeout(resolve, durationMs)),
      {
        loading: '正在上传处理文件...',
        success: () => {
          setStatus('success');
          schedule(() => {
            close();
            setStatus('idle');
            if (onSuccess) onSuccess(fileNameRef.current ?? undefined);
          }, successDurationMs);
          return '文件上传成功！';
        },
        error: '文件上传失败',
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

  const handleDrop = useCallback((e: DragEvent<HTMLDivElement>, onSuccess?: (fileName?: string) => void) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
    
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
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
    handleDragOver,
    handleDragLeave,
    handleDrop
  };
}
