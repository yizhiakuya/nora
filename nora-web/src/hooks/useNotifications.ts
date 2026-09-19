import { create } from "zustand";
import { persist } from "zustand/middleware";
import { usePreferences } from "./usePreferences";

export type NotificationEvent =
  | "taskDone"
  | "taskFail"
  | "indexed"
  | "svcError"
  | "general";

export interface AppNotification {
  id: number;
  title: string;
  detail: string;
  time: string;
  read: boolean;
  /** 事件类型：用于与「通知偏好」中的开关联动过滤。 */
  event: NotificationEvent;
}

const now = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

interface NotificationsState {
  notifications: AppNotification[];
  addNotification: (title: string, detail: string, event?: NotificationEvent) => void;
  markAllRead: () => void;
  /** 清空全部通知(通知中心「清空」按钮)。 */
  clearAll: () => void;
}

/**
 * 系统通知唯一数据源：自动任务执行、修复任务创建、文件索引入库等
 * 业务动作调用 addNotification，通知中心实时更新。初始为空——
 * 通知只来自真实业务事件，没有假种子。
 */
export const useNotifications = create<NotificationsState>()(
  persist(
    (set) => ({
      notifications: [],
      addNotification: (title, detail, event = "general") =>
        set((state) => {
          // 「通知偏好 → 事件开关」联动：被关闭的事件不再进入通知中心。
          const enabled = usePreferences.getState().notifications.events;
          if (event !== "general" && enabled[event] === false) return state;
          // 浏览器通知通道(2026-09-19 补全):开关开启且已授权时发系统级通知。
          // fire-and-forget——未授权/不支持时静默(站内通知不受影响)。
          if (usePreferences.getState().notifications.browser) {
            try {
              if (typeof Notification !== "undefined" && Notification.permission === "granted") {
                new Notification(title, { body: detail, tag: `nora-${event}` });
              }
            } catch {
              /* 系统通知失败不影响站内通知 */
            }
          }
          return {
            notifications: [
              { id: Date.now(), title, detail, time: now(), read: false, event },
              ...state.notifications,
            ].slice(0, 50),
          };
        }),
      markAllRead: () =>
        set((state) => ({ notifications: state.notifications.map((n) => ({ ...n, read: true })) })),
      clearAll: () => set({ notifications: [] }),
    }),
    { name: "notifications", version: 1,
      // v0 存过假种子通知,升级后清空,只留真实事件产生的通知
      migrate: (state) => ({ ...(state as NotificationsState), notifications: [] }) }
  )
);
