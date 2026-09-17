import { useCallback, useRef, useState } from "react";
import { FileItem, FilePreview } from "@/types";
import { filesApi } from "@/lib/services/filesApi";
import { USE_BACKEND } from "@/lib/api/client";

export type FileViewerStatus = "idle" | "loading" | "ready" | "error";

/**
 * 文件预览查看器状态机（2026-09-17 性能/灵动升级）。
 *
 * 能力：
 * - open(file) 弹窗秒开（loading 骨架），拉取预览后进入 ready；
 * - **预览缓存**：同文件二次打开直接命中（Map<id, preview>），零等待；
 * - **相邻预取**：打开后后台预取前后各一个文件的预览——按 ←/→ 切换即秒开；
 * - **导航**：attachList(files) 注入当前列表，navigate(±1) 切换文件；
 * - requestSeq 守卫竞态（快速切换时仅接受最新响应）。
 *
 * USE_BACKEND 时走 file-service 真实预览(Tika 文本提取)，否则 Mock。
 */
export function useFileViewer() {
  const [activeFile, setActiveFile] = useState<FileItem | null>(null);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [status, setStatus] = useState<FileViewerStatus>("idle");
  /** 当前列表（导航用）与当前下标。 */
  const [fileList, setFileList] = useState<FileItem[]>([]);
  const [index, setIndex] = useState(-1);
  const requestSeq = useRef(0);
  /** 预览缓存：fileId → preview（会话内；二次打开零等待）。
   *  上限 30 条 LRU——text/csv/word 的 preview 含 Tika 全量文本(可达 MB 级),
   *  无上限的话连续翻看大文档会在会话内持续增长。Map 迭代序 = 插入序,
   *  命中时删除重插即 LRU。 */
  const cache = useRef(new Map<number, FilePreview>());
  const CACHE_MAX = 30;
  const cachePut = (id: number, p: FilePreview) => {
    const m = cache.current;
    if (m.has(id)) m.delete(id);
    m.set(id, p);
    while (m.size > CACHE_MAX) {
      const oldest = m.keys().next().value;
      if (oldest === undefined) break;
      m.delete(oldest);
    }
  };
  const cacheGet = (id: number): FilePreview | undefined => {
    const m = cache.current;
    const v = m.get(id);
    if (v !== undefined) {
      // 命中即提升到最新(LRU 序)
      m.delete(id);
      m.set(id, v);
    }
    return v;
  };

  /** 注入当前上下文列表（文件页/首页各自传入；弹窗内 ←/→ 在此列表内切换）。 */
  const attachList = useCallback((files: FileItem[]) => {
    setFileList(files);
  }, []);

  /** 拉取预览（带缓存）；供 open / 预取共用。 */
  const fetchInto = useCallback(async (file: FileItem, seq: number) => {
    const cached = cacheGet(file.id);
    if (cached) {
      if (seq === requestSeq.current) {
        setPreview(cached);
        setStatus("ready");
      }
      return;
    }
    try {
      const data = await filesApi.fetchPreview(file.id, file.name);
      if (USE_BACKEND) cachePut(file.id, data);
      if (seq !== requestSeq.current) return; // 过期响应，丢弃
      setPreview(data);
      setStatus("ready");
    } catch {
      if (seq !== requestSeq.current) return;
      setStatus("error");
    }
  }, []);

  /** 后台预取（不改变当前状态；失败静默）。 */
  const prefetch = useCallback((file: FileItem | undefined) => {
    if (!file || cache.current.has(file.id)) return;
    void filesApi.fetchPreview(file.id, file.name)
      .then((data) => { if (USE_BACKEND) cachePut(file.id, data); })
      .catch(() => { /* 预取失败无感 */ });
  }, []);

  const open = useCallback(async (file: FileItem, list?: FileItem[]) => {
    const seq = ++requestSeq.current;
    setActiveFile(file);
    if (list) {
      setFileList(list);
      setIndex(list.findIndex((f) => f.id === file.id));
    }
    const cached = cacheGet(file.id);
    if (cached) {
      // 缓存命中：直接 ready（无骨架闪烁）
      setPreview(cached);
      setStatus("ready");
    } else {
      setPreview(null);
      setStatus("loading");
      await fetchInto(file, seq);
    }
    // 相邻预取（在列表内找前后）
    const currentList = list ?? fileList;
    const i = currentList.findIndex((f) => f.id === file.id);
    if (i >= 0) {
      prefetch(currentList[i - 1]);
      prefetch(currentList[i + 1]);
    }
  }, [fetchInto, prefetch, fileList]);

  /** 在注入的列表内切换（delta=±1）；到边界不循环。 */
  const navigate = useCallback((delta: number) => {
    if (fileList.length === 0) return;
    const currentIdx = index >= 0
      ? index
      : fileList.findIndex((f) => f.id === activeFile?.id);
    const next = currentIdx + delta;
    if (next < 0 || next >= fileList.length) return;
    setIndex(next);
    void open(fileList[next]);
  }, [fileList, index, activeFile, open]);

  const close = useCallback(() => {
    requestSeq.current++; // 使在途请求全部过期
    setActiveFile(null);
    setPreview(null);
    setStatus("idle");
  }, []);

  /** 当前是否可前后切换（弹窗箭头/键盘提示用）。 */
  const canNavigate = fileList.length > 1;

  return {
    activeFile,
    preview,
    status,
    open,
    close,
    attachList,
    navigate,
    canNavigate,
    hasPrev: canNavigate && index > 0,
    hasNext: canNavigate && index >= 0 && index < fileList.length - 1,
    position: canNavigate && index >= 0 ? { index: index + 1, total: fileList.length } : null,
  };
}
