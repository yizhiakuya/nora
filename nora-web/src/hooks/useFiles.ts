import { create } from "zustand";
import { FileItem } from "@/types";
import { filesApi } from "@/lib/services/filesApi";

interface FilesState {
  loading: boolean;
  syncError: string | null;
  files: FileItem[];
  markIndexed: (id: number) => void;
  /** 后端模式:用服务端 FileItem 覆盖同 id 行(或插入到头部) */
  syncFile: (file: FileItem) => void;
  /** 以服务端列表为准整体刷新(后端恢复在线时调用) */
  syncFromBackend: () => Promise<void>;
}

/**
 * 文件中心唯一数据源：服务端列表的内存视图，刷新后重新读取。
 */
let revision = 0;

export const useFiles = create<FilesState>()(
  (set) => ({
    files: [],
    loading: true,
    syncError: null,
    syncFromBackend: async () => {
      const request = ++revision;
      set({ loading: true });
      try {
        const files = await filesApi.listFiles();
        if (request === revision) set({ files, syncError: null });
      } catch (error) {
        if (request === revision) set({ syncError: error instanceof Error ? error.message : String(error) });
      } finally {
        if (request === revision) set({ loading: false });
      }
    },
    markIndexed: (id) =>
      set((state) => {
        revision++;
        return {
          loading: false,
          files: state.files.map((f) => (f.id === id ? { ...f, indexed: true } : f)),
        };
      }),
    syncFile: (file) =>
      set((state) => {
        revision++;
        const exists = state.files.some((f) => f.id === file.id);
        return {
          loading: false,
          files: exists
            ? state.files.map((f) => (f.id === file.id ? file : f))
            : [file, ...state.files],
        };
      }),
  })
);
