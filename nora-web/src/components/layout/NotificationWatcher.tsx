'use client';

import { useEffect } from "react";
import { useNotifications } from "@/hooks/useNotifications";

/**
 * 通知中心同步器(2026-09-19 架构升级为独立服务后)。
 *
 * 事件产生已上移到后端:Kafka 事件总线(业务服务 producer)→
 * notification-service 消费落库。前端这里只做一件事——轮询拉取
 * 服务端通知列表(60s;打开页面/切回时立即拉一次)。
 *
 * 此前的前端侧事件轮询(自动任务执行记录/PROC 守护事件)已全部移除:
 * 那些逻辑现在由 automation-service / env-service 作为 producer 完成,
 * 页面开不开都不丢事件。
 */
export function NotificationWatcher() {
  const syncFromBackend = useNotifications((s) => s.syncFromBackend);

  useEffect(() => {
    let disposed = false;

    const poll = () => {
      if (disposed) return;
      void syncFromBackend();
    };
    poll();
    const timer = setInterval(poll, 60_000);
    // 切回标签页时立即刷新(用户回来先看到最新)
    const onVisible = () => {
      if (document.visibilityState === "visible") poll();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      disposed = true;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [syncFromBackend]);

  return null;
}
