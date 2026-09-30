import { create } from "zustand";
import { useRecentFiles } from "./useRecentFiles";
import { ApiError } from "@/lib/api/client";
import { toast } from "sonner";
import type { FileItem, FilePreview, ViewerFile } from "@/types";
import { viewerApi } from "@/lib/services/viewerApi";
import { USE_BACKEND } from "@/lib/api/client";
import type { ChatRef } from "@/lib/chatRefs";

export type FileViewerStatus = "idle" | "loading" | "ready" | "error";
type Origin = { sessionId?: string; collection?: string[]; index?: number };
interface ViewerState {
  active: ViewerFile | null;
  tabs: ViewerFile[];
  preview: FilePreview | null;
  status: FileViewerStatus;
  error: string | null;
  retry: () => Promise<void>;
  isOpen: boolean;
  expanded: boolean;
  wide: boolean;
  width: number;
  origin: Origin;
  mode: "preview" | "source";
  editing: boolean;
  draft: string;
  saving: boolean;
  externalChange: boolean;
  pendingAction: (() => void) | null;
  pendingReference: { sessionId: string; ref: ChatRef } | null;
  newFiles: number;
  run: { id: string; sessionId: string; suppressed: boolean; focused: boolean; seen: Set<string> } | null;
  open: (file: FileItem, list?: FileItem[]) => Promise<void>;
  openTargets: (targets: string[], focus?: string, origin?: Origin, automatic?: boolean) => Promise<void>;
  openText: (name: string, text: string, origin?: Origin) => void;
  close: () => void;
  closeTab: (target: string) => void;
  select: (target: string) => void;
  navigate: (delta: number) => void;
  setWidth: (width: number) => void;
  setMode: (mode: "preview" | "source") => void;
  setExpanded: (value: boolean) => void;
  beginEdit: () => void;
  setDraft: (draft: string) => void;
  save: (overwrite?: boolean) => Promise<boolean>;
  cancelEdit: () => void;
  refresh: (checkOnly?: boolean) => Promise<void>;
  attach: (sessionId: string) => void;
  beginRun: (id: string, sessionId: string) => void;
  receiveFiles: (runId: string, stepId: string, files: ViewerFile[], focus?: string) => void;
  suppressAutoOpen: () => void;
  requestAction: (action: () => void) => void;
  discardAndContinue: () => void;
}

let requestSeq = 0;
const cache = new Map<string, FilePreview>();
function cachedPreview(file: ViewerFile): FilePreview | undefined {
  const key = `${file.target}@${file.version}`;
  const hit = cache.get(key);
  if (hit) { cache.delete(key); cache.set(key, hit); }
  return hit;
}
function cachePreview(file: ViewerFile, preview: FilePreview) {
  cache.set(`${file.target}@${file.version}`, preview);
  const bytes = () => Array.from(cache.values()).reduce((sum, item) => sum + (item.text?.length ?? 0) * 4, 0);
  while (cache.size > 30 || bytes() > 16 * 1024 * 1024) cache.delete(cache.keys().next().value!);
}
function mergeTabs(tabs: ViewerFile[], incoming: ViewerFile[], focus?: string): ViewerFile[] {
  const next = [...tabs];
  for (const file of [...incoming.filter(file => file.target !== focus), ...incoming.filter(file => file.target === focus)]) {
    const index = next.findIndex(tab => tab.target === file.target);
    const originalUrl = file.originalUrl ?? next[index]?.originalUrl;
    if (index >= 0) next.splice(index, 1);
    next.push({ ...file, originalUrl });
    if (next.length > 8) next.splice(next.findIndex(tab => tab.target !== focus), 1);
  }
  return next;
}
function savedWidth(): number {
  const value = Number(localStorage.getItem("nora-viewer-width"));
  return value >= 45 && value <= 70 ? value : 55;
}

export const useFileViewer = create<ViewerState>((set, get) => ({
  active: null, tabs: [], preview: null, status: "idle", error: null, isOpen: false,
  retry: async () => {}, expanded: false, wide: false, width: savedWidth(), origin: {}, mode: "preview",
  editing: false, draft: "", saving: false, externalChange: false, pendingAction: null,
  pendingReference: null, newFiles: 0, run: null,
  suppressAutoOpen: () => {
    const run = get().run;
    if (run && !run.suppressed) set({ run: { ...run, suppressed: true } });
  },
  requestAction: action => {
    const state = get();
    if (state.saving) return;
    if (state.editing && state.draft !== (state.preview?.text ?? "")) set({ pendingAction: action });
    else { set({ editing: false }); action(); }
  },
  discardAndContinue: () => {
    const action = get().pendingAction;
    set({ editing: false, pendingAction: null });
    action?.();
  },
  open: async (file, list) => {
    if (!USE_BACKEND) { toast.info("文件查看需要连接后端服务"); return; }
    await get().openTargets([`file:${file.id}`], undefined, { collection: list?.map(item => `file:${item.id}`) });
  },
  openTargets: async (targets, focus, origin = {}, automatic = false) => {
    if (!automatic) get().suppressAutoOpen();
    let loading: Promise<void> | undefined;
    get().requestAction(() => { loading = (async () => {
      const seq = ++requestSeq;
      const index = origin.collection?.indexOf(focus ?? targets[0]);
      set({ isOpen: true, status: "loading", error: null, retry: () => get().openTargets(targets, focus, origin), origin: { ...origin, index: index != null && index >= 0 ? index : origin.index }, newFiles: 0, mode: "preview", externalChange: false });
      try {
        const resolved = await viewerApi.resolve(targets);
        if (seq !== requestSeq) return;
        const incoming = resolved.files.map(file => {
          const previous = get().tabs.find(tab => tab.target === file.target);
          return { ...file, delivery: file.delivery ?? previous?.delivery ?? null, originalUrl: file.originalUrl ?? previous?.originalUrl };
        });
        const file = incoming.find(item => item.target === focus) ?? incoming[0];
        if (!file) throw new Error(resolved.errors[0]?.message ?? "文件无法打开");
        if (resolved.errors.length) toast.warning(`${resolved.errors.length} 个文件无法打开`, { description: resolved.errors.map(item => item.message).join("；") });
        const tabs = mergeTabs(get().tabs, incoming, file.target);
        set({ active: file, tabs, preview: null });
        const preview = cachedPreview(file) ?? await viewerApi.preview(file);
        cachePreview(file, preview);
        if (seq === requestSeq) {
          set({ preview, status: "ready" });
          useRecentFiles.getState().addRecent(file.name, file.mimeType, file.target);
        }
      } catch (error) {
        if (seq === requestSeq) set({ active: null, preview: null, status: "error", error: error instanceof Error ? error.message : "读取失败" });
      }
    })(); });
    await loading;
  },
  openText: (name, text, origin = {}) => get().requestAction(() => {
    requestSeq++;
    const file: ViewerFile = { target: "history:temporary", name, mimeType: "text/markdown", size: new TextEncoder().encode(text).length,
      modifiedAt: null, version: "temporary", previewKind: "markdown", capabilities: { preview: true, source: true, download: false, edit: false, attach: false } };
    set({ active: file, preview: { kind: "markdown", text }, isOpen: true, status: "ready", error: null, origin, mode: "preview" });
    get().suppressAutoOpen();
  }),
  close: () => {
    get().suppressAutoOpen();
    get().requestAction(() => { requestSeq++; set({ isOpen: false, expanded: false, pendingAction: null }); });
  },
  closeTab: target => get().requestAction(() => {
    get().suppressAutoOpen();
    const next = get().tabs.filter(file => file.target !== target);
    set({ tabs: next });
    // 最后一个标签页:无论 active 是否匹配都关闭面板——文件解析失败时
    // active 为 null,旧逻辑的 active?.target === target 判定会漏掉,面板
    // 停在「无法打开文件」错误态关不掉(2026-09-30 实测:关已删文件标签后残留)
    if (!next.length) {
      get().close();
      return;
    }
    // active 为 null(上一个文件解析失败)时也选中剩余标签,给出恢复路径
    if (get().active?.target === target || !get().active) {
      get().select(next[next.length - 1].target);
    }
  }),
  select: target => {
    const origin = get().origin;
    const index = origin.collection?.indexOf(target);
    void get().openTargets([target], target, index === -1 ? { sessionId: origin.sessionId } : { ...origin, index });
  },
  navigate: delta => {
    const state = get();
    const list = state.origin.collection ?? state.tabs.map(file => file.target);
    const index = state.origin.index ?? list.indexOf(state.active?.target ?? "");
    if (index >= 0 && list[index + delta]) state.select(list[index + delta]);
  },
  setWidth: width => { get().suppressAutoOpen(); const value = Math.max(45, Math.min(70, width)); localStorage.setItem("nora-viewer-width", String(value)); set({ width: value }); },
  setMode: mode => { get().suppressAutoOpen(); set({ mode }); },
  setExpanded: expanded => { get().suppressAutoOpen(); set({ expanded }); },
  beginEdit: () => {
    if (!get().active?.capabilities.edit || get().preview?.truncated) return;
    get().suppressAutoOpen();
    set({ editing: true, draft: get().preview?.text ?? "", mode: "source" });
  },
  setDraft: draft => set({ draft }),
  save: async (overwrite = false) => {
    const state = get();
    if (!state.active || state.saving) return false;
    set({ saving: true });
    try {
      await viewerApi.save(state.active, state.draft, overwrite ? null : state.preview?.hash ?? null);
      set({ editing: false, externalChange: false });
      await get().refresh();
      toast.success("文件已保存");
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.code === 409) set({ externalChange: true });
      toast.error(error instanceof Error ? error.message : "保存失败");
      return false;
    } finally { set({ saving: false }); }
  },
  cancelEdit: () => get().requestAction(() => set({ editing: false })),
  refresh: async (checkOnly = false) => {
    const state = get();
    if (!state.active || state.active.target.startsWith("history:") || !state.isOpen) return;
    try {
      const { files, errors } = await viewerApi.resolve([state.active.target]);
      const resolved = files[0];
      if (!resolved) throw new Error(errors[0]?.message ?? "文件已不可用");
      const file = { ...resolved, delivery: resolved.delivery ?? state.active.delivery, originalUrl: resolved.originalUrl ?? state.active.originalUrl };
      if (get().active?.target !== file.target) return;
      if (checkOnly && file.version === state.active.version) return;
      if (get().editing) { set({ externalChange: true }); return; }
      const seq = ++requestSeq;
      set({ status: "loading" });
      const preview = await viewerApi.preview(file);
      if (seq !== requestSeq) return;
      cachePreview(file, preview);
      set({ active: file, preview, status: "ready", error: null, tabs: mergeTabs(get().tabs, [file], file.target) });
      if (checkOnly) toast.info("文件已更新");
    } catch (error) {
      if (get().active?.target !== state.active.target) return;
      if (get().editing) { set({ externalChange: true }); return; }
      set({ status: "error", preview: null, error: error instanceof Error ? error.message : "刷新失败" });
    }
  },
  attach: sessionId => {
    const file = get().active;
    if (!file?.capabilities.attach) return;
    const ref: ChatRef = file.target.startsWith("file:") ? { kind: "file", id: Number(file.target.slice(5)), name: file.name }
      : { kind: "viewer", id: file.target, name: file.name };
    set({ pendingReference: { sessionId, ref }, expanded: false });
    if (!get().wide) get().close();
  },
  beginRun: (id, sessionId) => set({ run: { id, sessionId, suppressed: get().editing, focused: false, seen: new Set() } }),
  receiveFiles: (runId, stepId, files, focus) => {
    const state = get(), run = state.run;
    if (!run || run.id !== runId || run.seen.has(stepId) || !files.length) return;
    const seen = new Set(run.seen).add(stepId);
    const shouldOpen = state.wide && !run.focused && !run.suppressed;
    set({ run: { ...run, seen, focused: run.focused || shouldOpen }, newFiles: state.newFiles + files.length,
      ...(!state.editing ? { tabs: mergeTabs(state.tabs, files, shouldOpen ? focus : state.active?.target) } : {}) });
    if (shouldOpen) void get().openTargets(files.map(file => file.target), focus, { sessionId: run.sessionId }, true);
  },
}));
