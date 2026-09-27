import { create } from "zustand";

/**
 * 全局搜索面板开关(§7 评审,2026-09-27)。
 *
 * 此前 CommandPalette 与 Cmd+K 监听都注册在首页组件里——离开首页后按
 * 同一方式打不开「全局搜索」。现在面板实例挂在 RouteShell(全局),
 * 开关状态放这里:任何页面(含首页搜索框点击)都打开同一个实例。
 */
interface CommandPaletteState {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;
}

export const useCommandPalette = create<CommandPaletteState>()((set) => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((s) => ({ isOpen: !s.isOpen })),
}));
