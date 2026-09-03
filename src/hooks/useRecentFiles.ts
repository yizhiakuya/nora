import { create } from "zustand";
import { persist } from "zustand/middleware";

export interface RecentFile {
  name: string;
  type: string;
  time: string;
}

interface RecentFilesState {
  recent: RecentFile[];
  addRecent: (name: string, type: string) => void;
}

const SEED: RecentFile[] = [
  { name: "NestJS部署手册.pdf", type: "PDF 文档", time: "10 分钟前" },
  { name: "服务器巡检记录.xlsx", type: "Excel 表格", time: "2 小时前" },
];

/** 最近使用文件：文件中心打开/上传时写入，首页展示（持久化）。 */
export const useRecentFiles = create<RecentFilesState>()(
  persist(
    (set) => ({
      recent: SEED,
      addRecent: (name, type) =>
        set((state) => ({
          recent: [{ name, type, time: "刚刚" }, ...state.recent.filter((r) => r.name !== name)].slice(0, 5),
        })),
    }),
    { name: "recent-files" }
  )
);
