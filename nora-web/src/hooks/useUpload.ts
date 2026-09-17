import { useState, useCallback, useRef, DragEvent } from 'react';
import { toast } from 'sonner';
import { useTimedSequence } from './useTimedSequence';
import { filesApi } from '@/lib/services/filesApi';
import { USE_BACKEND } from '@/lib/api/client';
import { FileItem } from '@/types';

type UploadStatus = 'idle' | 'uploading' | 'success' | 'error';

/** 多文件上传进度(弹窗展示用)。 */
export interface UploadProgress {
  total: number;
  done: number;
  failed: number;
  /** 当前正在上传的文件名 */
  current: string | null;
}

/**
 * 上传状态机:USE_BACKEND=true 时真实上传到 file-service,否则本地模拟。
 * 后端模式下 onUploadComplete 收到完整的 FileItem(含服务端 id);
 * Mock 模式保持旧行为(只回传文件名)。
 *
 * 支持**多文件**:点击/拖拽可一次选多个,逐个上传(串行,进度可见);
 * 全部完成后统一回调(onUploadComplete 每个文件各调一次,批量汇总另给
 * onUploadBatchComplete)。
 */
export function useSimulatedUpload(durationMs: number = 2000, successDurationMs: number = 1500) {
  const [isOpen, setIsOpen] = useState(false);
  const [status, setStatus] = useState<UploadStatus>('idle');
  const [isDragging, setIsDragging] = useState(false);
  const [progress, setProgress] = useState<UploadProgress | null>(null);
  const { schedule, cancelAll } = useTimedSequence();
  /** 待上传的真实文件(后端模式上传它们;点击上传无文件保持空) */
  const filesRef = useRef<globalThis.File[]>([]);
  const fileNameRef = useRef<string | null>(null);
  /** 上传目标文件夹(文件页进入某文件夹时设置;null = 根目录)。 */
  const folderRef = useRef<number | null>(null);
  /** 隐藏的 file input(点击区域触发系统多选对话框)。 */
  const inputRef = useRef<HTMLInputElement | null>(null);

  const open = useCallback(() => {
    setIsOpen(true);
    setStatus('idle');
    setIsDragging(false);
    setProgress(null);
    filesRef.current = [];
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
    setProgress(null);
  }, [cancelAll]);

  /**
   * 开始上传(支持多文件)。
   *
   * @param onSuccess 每个文件成功后的回调(文件名, FileItem)
   * @param onBatch   全部完成后的汇总回调(成功数, 失败数)
   */
  const startUpload = useCallback((
    onSuccess?: (fileName?: string, file?: FileItem) => void,
    onBatch?: (okCount: number, failCount: number) => void,
  ) => {
    if (status !== 'idle') return;
    const picked = filesRef.current;
    if (picked.length === 0) {
      // 无文件(点击上传的 mock 路径):保持旧的模拟行为
      setStatus('uploading');
      setTimeout(() => {
        setStatus('success');
        schedule(() => {
          close();
          setStatus('idle');
          onSuccess?.(undefined, undefined);
        }, successDurationMs);
      }, durationMs);
      return;
    }

    setStatus('uploading');
    setProgress({ total: picked.length, done: 0, failed: 0, current: picked[0]?.name ?? null });

    // 串行上传:逐个进行,进度可见;单个失败不阻断后续
    void (async () => {
      let ok = 0;
      let fail = 0;
      for (const f of picked) {
        setProgress((p) => p ? { ...p, current: f.name } : p);
        try {
          const item = USE_BACKEND
            ? await filesApi.uploadFile(f, folderRef.current)
            // mock 模式:返回 null(不是空对象!)——空对象会让文件页把它当真实
            // 文件 syncFile 进 store(id/name/icon 全 undefined),列表渲染 Icon
            // 为 undefined 直接崩页面(实测回归)。
            : await new Promise<FileItem | null>((resolve) => setTimeout(() => resolve(null), 300));
          ok++;
          setProgress((p) => p ? { ...p, done: p.done + 1, current: null } : p);
          onSuccess?.(f.name, item ?? undefined);
        } catch (e) {
          fail++;
          setProgress((p) => p ? { ...p, done: p.done + 1, failed: p.failed + 1, current: null } : p);
          toast.error(`「${f.name}」上传失败：${e instanceof Error ? e.message : "未知错误"}`);
        }
      }
      // 状态反映真实结果:全部失败时进 error 态(不再无条件打绿勾"上传成功"——
      // 失败只体现在 toast 里,弹窗却显示大对勾,用户误以为已入库)
      setStatus(ok === 0 && fail > 0 ? 'error' : 'success');
      schedule(() => {
        close();
        setStatus('idle');
        onBatch?.(ok, fail);
      }, successDurationMs);
    })();
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
      // 支持多文件拖入:全部接收
      filesRef.current = Array.from(e.dataTransfer.files);
      fileNameRef.current = filesRef.current[0]?.name ?? null;
      startUpload(onSuccess);
    }
  }, [startUpload]);

  /** 打开系统文件选择对话框(支持多选);选择后自动开始上传。 */
  const pickAndUpload = useCallback((onSuccess?: (fileName?: string, file?: FileItem) => void,
                                     onBatch?: (okCount: number, failCount: number) => void) => {
    const input = inputRef.current;
    if (!input) return;
    input.value = "";
    input.onchange = () => {
      const picked = Array.from(input.files ?? []);
      if (picked.length === 0) return;
      filesRef.current = picked;
      fileNameRef.current = picked[0]?.name ?? null;
      startUpload(onSuccess, onBatch);
    };
    input.click();
  }, [startUpload]);

  return {
    isOpen,
    status,
    isDragging,
    progress,
    /** 隐藏 input 的 ref(UploadModal 挂载) */
    inputRef,
    open,
    close,
    startUpload,
    pickAndUpload,
    setTargetFolder,
    handleDragOver,
    handleDragLeave,
    handleDrop
  };
}
