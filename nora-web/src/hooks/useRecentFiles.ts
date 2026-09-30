import { create } from "zustand";
import { persist } from "zustand/middleware";

export interface RecentFile {
  target?: string;
  name: string;
  type: string;
  time: string;
}

interface RecentFilesState {
  recent: RecentFile[];
  addRecent: (name: string, type: string, target?: string) => void;
}

/** 最近使用文件：文件中心打开/上传时写入，首页展示（持久化）。初始为空——后端是唯一数据源，没有假种子。 */
export const useRecentFiles = create<RecentFilesState>()(
  persist(
    (set) => ({
      recent: [],
      addRecent: (name, type, target) =>
        set((state) => ({
          recent: [{ name, type, target, time: "刚刚" }, ...state.recent.filter((r) => target ? r.target !== target : r.name !== name)].slice(0, 5),
        })),
    }),
    { name: "recent-files", version: 1,
      // v0 存过假种子数据(NestJS部署手册等),升级后清空,只留真实使用记录
      migrate: (state) => ({ ...(state as RecentFilesState), recent: [] }) }
  )
);
