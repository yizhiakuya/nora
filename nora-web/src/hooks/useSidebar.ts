import { create } from "zustand";
import { persist } from "zustand/middleware";

interface SidebarState {
  /** 当前展开二级菜单的导航项 key（null = 全部收起） */
  expandedNav: string | null;
  isOpen: boolean;
  isCollapsed: boolean;
  setIsOpen: (isOpen: boolean) => void;
  toggle: () => void;
  setCollapsed: (v: boolean) => void;
  setExpandedNav: (key: string | null) => void;
  toggleCollapsed: () => void;
}

export const useSidebarStore = create<SidebarState>()(
  persist(
    (set) => ({
      expandedNav: null,
      isOpen: false,
      isCollapsed: false,
      setIsOpen: (isOpen) => set({ isOpen }),
      toggle: () => set((s) => ({ isOpen: !s.isOpen })),
      setCollapsed: (isCollapsed) => set({ isCollapsed }),
      setExpandedNav: (expandedNav) => set({ expandedNav }),
      toggleCollapsed: () => set((s) => ({ isCollapsed: !s.isCollapsed })),
    }),
    {
      name: "sidebar-storage",
      partialize: (s) => ({ isCollapsed: s.isCollapsed, expandedNav: s.expandedNav }),
    }
  )
);


