import { requestJson, USE_BACKEND } from "@/lib/api/client";

/**
 * 通知中心 API(2026-09-19 notification-service):
 * 事件产生走 Kafka(业务服务 producer),这里只读与管理。
 * 已读状态存服务端,跨浏览器一致。
 */
export interface ServerNotification {
  id: number;
  event: string;
  title: string;
  detail: string | null;
  source: string | null;
  read: boolean;
  createdAt: string;
}

export const notificationsApi = {
  /** 最近通知(最新在前)。 */
  async list(limit = 50): Promise<ServerNotification[]> {
    if (!USE_BACKEND) return [];
    return requestJson<ServerNotification[]>(`/notifications?limit=${limit}`);
  },

  /** 前端上报一条通知(上传完成/数据源连接等用户当前动作)。 */
  async create(event: string, title: string, detail: string): Promise<number> {
    if (!USE_BACKEND) return 0;
    return requestJson<number>("/notifications", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ event, title, detail }),
    });
  },

  /** 全部标记已读。 */
  async markAllRead(): Promise<number> {
    if (!USE_BACKEND) return 0;
    return requestJson<number>("/notifications/read-all", { method: "POST" });
  },

  /** 单条标记已读。 */
  async markRead(id: number): Promise<number> {
    if (!USE_BACKEND) return 0;
    return requestJson<number>(`/notifications/${id}/read`, { method: "POST" });
  },

  /** 清空全部。 */
  async clearAll(): Promise<number> {
    if (!USE_BACKEND) return 0;
    return requestJson<number>("/notifications", { method: "DELETE" });
  },
};
