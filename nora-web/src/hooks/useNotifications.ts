import { toast } from "sonner";
import { create } from "zustand";
import { usePreferences } from "./usePreferences";
import { notificationsApi, type ServerNotification } from "@/lib/services/notificationsApi";
import { nowHm, hmFromLocalIso } from "@/lib/format";

type NotificationEvent =
  | "taskDone"
  | "taskFail"
  | "indexed"
  | "svcError"
  | "general";

interface AppNotification {
  id: number;
  title: string;
  detail: string;
  time: string;
  read: boolean;
  /** 事件类型：用于与「通知偏好」中的开关联动过滤。 */
  event: NotificationEvent;
}

/** 通知仅展示服务端事实，写入与同步失败保留原列表并提示。 */
interface NotificationsState {
  notifications: AppNotification[];
  loading: boolean;
  syncError: string | null;
  writing: boolean;
  /** 服务端轮询同步(后端模式;NotificationWatcher 调用)。 */
  syncFromBackend: () => Promise<void>;
  addNotification: (title: string, detail: string, event?: NotificationEvent) => Promise<void>;
  markAllRead: () => Promise<void>;
  /** 清空全部通知。 */
  clearAll: () => Promise<void>;
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
export function sendBrowserNotification(title: string, detail: string, event: NotificationEvent): void {
  if (!eventEnabled(event) || !usePreferences.getState().notifications.browser) return;
  try {
    if (typeof Notification !== "undefined" && Notification.permission === "granted") {
      new Notification(title, { body: detail, tag: `nora-${event}` });
    }
  } catch {
    /* 系统通知失败不影响站内通知 */
  }
}

let revision = 0;
let pendingWrites = 0;

export const useNotifications = create<NotificationsState>()((set, get) => ({
  notifications: [],
  loading: true,
  syncError: null,
  writing: false,
  syncFromBackend: async () => {
    if (pendingWrites > 0) return;
    const request = ++revision;
    set({ loading: true });
    try {
      const rows = await notificationsApi.list(50);
      if (request === revision) set({ notifications: rows.map(toView), syncError: null });
    } catch (error) {
      if (request === revision) set({ syncError: error instanceof Error ? error.message : String(error) });
    } finally {
      if (request === revision) set({ loading: false });
    }
  },
  addNotification: async (title, detail, event = "general") => {
    if (!eventEnabled(event)) return;
    sendBrowserNotification(title, detail, event);
    pendingWrites++; revision++;
    try {
      await notificationsApi.create(event, title, detail);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error));
    } finally {
      pendingWrites--; revision++;
      await get().syncFromBackend();
    }
  },
  markAllRead: async () => {
    if (get().writing) return;
    set({ writing: true });
    pendingWrites++; revision++;
    try {
      await notificationsApi.markAllRead();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error));
    } finally {
      pendingWrites--; revision++;
      await get().syncFromBackend();
      set({ writing: false });
    }
  },
  clearAll: async () => {
    if (get().writing) return;
    set({ writing: true });
    pendingWrites++; revision++;
    try {
      await notificationsApi.clearAll();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error));
    } finally {
      pendingWrites--; revision++;
      await get().syncFromBackend();
      set({ writing: false });
    }
  },
}));
