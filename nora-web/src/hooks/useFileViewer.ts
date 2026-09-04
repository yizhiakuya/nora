import { useCallback, useRef, useState } from "react";
import { FileItem, FilePreview } from "@/types";
import { MockAPI } from "@/lib/api/mockApi";

export type FileViewerStatus = "idle" | "loading" | "ready" | "error";

/**
 * 文件预览查看器状态机：
 * open(file) 弹窗秒开（loading 骨架），Mock 拉取预览后进入 ready；
 * requestSeq 守卫竞态（快速切换文件时仅接受最新响应）。
 */
export function useFileViewer() {
  const [activeFile, setActiveFile] = useState<FileItem | null>(null);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [status, setStatus] = useState<FileViewerStatus>("idle");
  const requestSeq = useRef(0);

  const open = useCallback(async (file: FileItem) => {
    const seq = ++requestSeq.current;
    setActiveFile(file);
    setPreview(null);
    setStatus("loading");
    try {
      const data = await MockAPI.files.getPreview(file);
      if (seq !== requestSeq.current) return; // 过期响应，丢弃
      setPreview(data);
      setStatus("ready");
    } catch {
      if (seq !== requestSeq.current) return;
      setStatus("error");
    }
  }, []);

  const close = useCallback(() => {
    requestSeq.current++; // 使在途请求全部过期
    setActiveFile(null);
    setPreview(null);
    setStatus("idle");
  }, []);

  return { activeFile, preview, status, open, close };
}
