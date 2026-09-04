import { create } from "zustand";
import { persist } from "zustand/middleware";
import { FileItem } from "@/types";
import { MOCK_FILES } from "@/lib/mockData";
import { FileText, FileSpreadsheet, FileImage, File } from "lucide-react";

type PersistedFile = Omit<FileItem, "icon">;
type PersistedFilesState = { files: PersistedFile[] };

function getFileMeta(name: string, type?: string) {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["xlsx", "csv"].includes(ext)) {
    return { type: type || "Excel 表格", icon: FileSpreadsheet, color: "text-green-600 dark:text-green-400" };
  }
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext)) {
    return { type: type || "PNG 图像", icon: FileImage, color: "text-purple-500 dark:text-purple-400" };
  }
  if (["docx", "doc"].includes(ext)) {
    return { type: type || "Word 文档", icon: FileText, color: "text-blue-500 dark:text-blue-400" };
  }
  if (["txt", "md"].includes(ext)) {
    return { type: type || "纯文本", icon: File, color: "text-gray-500 dark:text-gray-400" };
  }
  return { type: type || "PDF 文档", icon: FileText, color: "text-red-500 dark:text-red-400" };
}

function restoreFile(f: PersistedFile): FileItem {
  const meta = getFileMeta(f.name, f.type);
  return {
    ...f,
    icon: meta.icon,
    color: f.color || meta.color,
  };
}

function stripFile(f: FileItem): PersistedFile {
  const { icon, ...rest } = f;
  return rest;
}

interface FilesState {
  files: FileItem[];
  addFile: (name: string, size?: string) => FileItem;
  deleteFiles: (ids: number[]) => void;
  markIndexed: (id: number) => void;
  /** 后端模式:用服务端 FileItem 覆盖同 id 行(或插入到头部) */
  syncFile: (file: FileItem) => void;
}

/**
 * 文件中心唯一数据源：持久化存储上传与索引状态，刷新不丢失。
 */
export const useFiles = create<FilesState>()(
  persist<FilesState, [], [], PersistedFilesState>(
    (set) => ({
      files: MOCK_FILES,
      addFile: (name, size = "1.5 MB") => {
        const meta = getFileMeta(name);
        const newFile: FileItem = {
          id: Date.now(),
          name,
          type: meta.type,
          size,
          date: new Date().toISOString().slice(0, 16).replace("T", " "),
          icon: meta.icon,
          color: meta.color,
          indexed: false,
        };
        set((state) => ({ files: [newFile, ...state.files] }));
        return newFile;
      },
      deleteFiles: (ids) =>
        set((state) => ({ files: state.files.filter((f) => !ids.includes(f.id)) })),
      markIndexed: (id) =>
        set((state) => ({
          files: state.files.map((f) => (f.id === id ? { ...f, indexed: true } : f)),
        })),
      syncFile: (file) =>
        set((state) => {
          const exists = state.files.some((f) => f.id === file.id);
          return {
            files: exists
              ? state.files.map((f) => (f.id === file.id ? file : f))
              : [file, ...state.files],
          };
        }),
    }),
    {
      name: "nora-files",
      partialize: (state): PersistedFilesState => ({
        files: state.files.map(stripFile),
      }),
      merge: (persisted, current) => {
        const p = (persisted ?? {}) as Partial<PersistedFilesState>;
        const files = (p.files ?? current.files.map(stripFile)).map(restoreFile);
        return { ...current, files };
      },
    }
  )
);
