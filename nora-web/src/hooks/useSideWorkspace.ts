import { create } from "zustand";

/** 面板的布局与标签选择；文件内容、浏览器和命令数据各自管理。 */
interface WorkspaceState {
  isOpen: boolean;
  panel: "file" | "browser" | "tasks";
  browserOpened: boolean;
  tasksOpened: boolean;
  focusedCommand: string | null;
  expanded: boolean;
  wide: boolean;
  width: number;
  show: (panel: WorkspaceState["panel"]) => void;
  hide: () => void;
  setExpanded: (expanded: boolean) => void;
  setWidth: (width: number) => void;
}
const saved = Number(localStorage.getItem("nora-viewer-width"));
export const useSideWorkspace = create<WorkspaceState>(set => ({
  isOpen: false, panel: "file", browserOpened: false, tasksOpened: false, focusedCommand: null,
  expanded: false, wide: false, width: saved >= 45 && saved <= 70 ? saved : 55,
  show: panel => set({ isOpen: true, panel, ...(panel === "browser" ? { browserOpened: true } : panel === "tasks" ? { tasksOpened: true } : {}) }),
  hide: () => set({ isOpen: false, expanded: false }),
  setExpanded: expanded => set({ expanded }),
  setWidth: width => {
    const value = Math.max(45, Math.min(70, width));
    localStorage.setItem("nora-viewer-width", String(value));
    set({ width: value });
  },
}));
