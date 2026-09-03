import { create } from "zustand";

export interface AppNotification {
  id: number;
  title: string;
  detail: string;
  time: string;
  read: boolean;
}

const now = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

const SEED: AppNotification[] = [
  { id: 1, title: "任务执行完成", detail: "自动任务「每日数据备份」已成功完成，耗时 42s。", time: "10 分钟前", read: false },
  { id: 2, title: "系统提醒", detail: "知识库上次全量索引为 2 小时前，3 个文档待处理。", time: "2 小时前", read: false },
];

interface NotificationsState {
  notifications: AppNotification[];
  addNotification: (title: string, detail: string) => void;
  markAllRead: () => void;
}

/**
 * 系统通知唯一数据源：自动任务执行、修复任务创建、文件索引入库等
 * 业务动作调用 addNotification，通知中心实时更新。
 */
export const useNotifications = create<NotificationsState>((set) => ({
  notifications: SEED,
  addNotification: (title, detail) =>
    set((state) => ({
      notifications: [{ id: Date.now(), title, detail, time: now(), read: false }, ...state.notifications].slice(0, 50),
    })),
  markAllRead: () =>
    set((state) => ({ notifications: state.notifications.map((n) => ({ ...n, read: true })) })),
}));
