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
    {
      name: "sidebar-storage",
      partialize: (s) => ({ isCollapsed: s.isCollapsed }),
    }
  )
);
