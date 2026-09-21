import { create } from "zustand";
import { usePreferences } from "./usePreferences";
import { notificationsApi, type ServerNotification } from "@/lib/services/notificationsApi";
import { USE_BACKEND } from "@/lib/api/client";
import { nowHm, hmFromLocalIso } from "@/lib/format";

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

/**
 * 系统通知(2026-09-19 独立服务):notification-service 为唯一数据源——
 * 后端事件(自动任务/进程守护/索引)走 Kafka 消费落库,前端动作(上传完成等)
 * 经 POST /api/notifications 上报;已读状态在服务端,跨浏览器一致。
 *
 * 本 store 是服务端数据的客户端镜像:
 * - syncFromBackend:轮询拉取(NotificationWatcher 挂载时启动,60s);
 * - addNotification:上报服务端 + 乐观本地插入(立即出角标,失败静默——
 *   通知是增益,不打断用户动作);
 * - Mock 模式(USE_BACKEND=false):纯本地行为。
 */
interface NotificationsState {
  notifications: AppNotification[];
  /** 服务端轮询同步(后端模式;NotificationWatcher 调用)。 */
  syncFromBackend: () => Promise<void>;
  addNotification: (title: string, detail: string, event?: NotificationEvent) => void;
  markAllRead: () => void;
  /** 清空全部通知。 */
  clearAll: () => void;
}

/** 服务端行 → 前端视图(时间取 HH:mm)。 */
function toView(n: ServerNotification): AppNotification {
  return {
    id: n.id,
    title: n.title,
    detail: n.detail ?? "",
    // 后端 ISO 串是本地挂钟(无时区后缀):走 hmFromLocalIso 截取,不用 new Date(会被贴错时区)
    time: hmFromLocalIso(n.createdAt) || nowHm(),
    read: n.read,
    event: (n.event as NotificationEvent) ?? "general",
  };
}

/** 事件是否被「通知偏好 → 事件开关」放行。 */
function eventEnabled(event: NotificationEvent): boolean {
  if (event === "general") return true;
  return usePreferences.getState().notifications.events[event] !== false;
}

/** 浏览器通知通道:开关开启且已授权时发系统级通知(fire-and-forget)。 */
function sendBrowserNotification(title: string, detail: string, event: NotificationEvent): void {
  if (!usePreferences.getState().notifications.browser) return;
  try {
    if (typeof Notification !== "undefined" && Notification.permission === "granted") {
      new Notification(title, { body: detail, tag: `nora-${event}` });
    }
  } catch {
    /* 系统通知失败不影响站内通知 */
  }
}

export const useNotifications = create<NotificationsState>()((set, get) => {
  // 旧版 persist 残留清理(2026-09-19 架构升级):通知以服务端为准,
  // localStorage 不再存通知;旧键留着只会让旧数据"诈尸"。
  try {
    localStorage.removeItem("notifications");
  } catch {
    /* 隐私模式等场景存储不可用 */
  }
  return {
  notifications: [],

  syncFromBackend: async () => {
    if (!USE_BACKEND) return;
    try {
      const rows = await notificationsApi.list(50);
      // 服务端为准;但保留本地乐观插入的临时负 id 项(上报成功前的短暂窗口)
      const serverViews = rows.map(toView);
      const optimistic = get().notifications.filter((n) => n.id < 0);
      set({ notifications: [...optimistic, ...serverViews].slice(0, 50) });
    } catch {
      /* 后端不可达:沿用现有列表 */
    }
  },

  addNotification: (title, detail, event = "general") => {
    // 「通知偏好 → 事件开关」联动：被关闭的事件不入列、不上报。
    if (!eventEnabled(event)) return;
    sendBrowserNotification(title, detail, event);
    // 乐观本地插入(负 id 占位,立即出角标);服务端同步后由真实行替换
    const localId = -Date.now();
    set((state) => ({
      notifications: [
        { id: localId, title, detail, time: nowHm(), read: false, event },
        ...state.notifications,
      ].slice(0, 50),
    }));
    if (USE_BACKEND) {
      // 上报服务端(跨浏览器一致);成功后移除乐观项并拉一次同步换真实行,
      // 失败静默(乐观项保留,角标仍可见)
      void notificationsApi.create(event, title, detail)
        .then(() => {
          set((state) => ({ notifications: state.notifications.filter((n) => n.id !== localId) }));
          return get().syncFromBackend();
        })
        .catch(() => { /* 通知上报失败静默 */ });
    }
  },

  markAllRead: () => {
    set((state) => ({ notifications: state.notifications.map((n) => ({ ...n, read: true })) }));
    if (USE_BACKEND) {
      void notificationsApi.markAllRead().catch(() => { /* 静默 */ });
    }
  },

  clearAll: () => {
    set({ notifications: [] });
    if (USE_BACKEND) {
      void notificationsApi.clearAll().catch(() => { /* 静默 */ });
    }
  },
  };
});
