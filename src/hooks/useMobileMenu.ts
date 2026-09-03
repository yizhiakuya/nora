import { create } from "zustand";
import { persist } from "zustand/middleware";

interface SidebarState {
  isOpen: boolean;
  isCollapsed: boolean;
  setIsOpen: (isOpen: boolean) => void;
  toggle: () => void;
  setCollapsed: (v: boolean) => void;
  toggleCollapsed: () => void;
}

export const useSidebarStore = create<SidebarState>()(
  persist(
    (set) => ({
      isOpen: false,
      isCollapsed: false,
      setIsOpen: (isOpen) => set({ isOpen }),
      toggle: () => set((s) => ({ isOpen: !s.isOpen })),
      setCollapsed: (isCollapsed) => set({ isCollapsed }),
      toggleCollapsed: () => set((s) => ({ isCollapsed: !s.isCollapsed })),
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    { name: "sidebar-storage", partialize: (s) => ({ isCollapsed: s.isCollapsed }) as any }
  )
);

// 兼容旧命名，Header/Sidebar 仍可用 useMobileMenu
export const useMobileMenu = useSidebarStore;
