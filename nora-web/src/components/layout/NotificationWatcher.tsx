'use client';

import { useEffect } from "react";
import { useNotifications } from "@/hooks/useNotifications";
import { automationsApi } from "@/lib/services/automationsApi";
import { environmentApi } from "@/lib/services/environmentApi";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 全局后台事件观察器(2026-09-19 补全):无论用户停在哪个页面,都能收到
 * 后台产生的通知——此前自动任务执行/PROC 守护事件只在对应页面打开时
 * 才被轮询,其他页面收不到(用户截图"暂无通知"的根因)。
 *
 * 覆盖两条事件源(60s 轮询,后台事件不紧急):
 * - 自动任务执行记录(定时 daily/weekly 由后端调度器跑,与页面无关);
 * - PROC 守护事件(进程死亡/自愈/启动失败)。
 *
 * 基线持久化(localStorage):刷新后从上次位置继续,既不轰炸历史、
 * 也不漏掉"页面关闭期间"产生的事件。首次运行只记基线不通知。
 */
const EXEC_BASELINE_KEY = "nora-notif-exec-baseline";
const PROC_CURSOR_KEY = "nora-notif-proc-cursor";

function readNum(key: string): number | null {
  try {
    const v = localStorage.getItem(key);
    return v == null ? null : Number(v);
  } catch {
    return null;
  }
}

function readStr(key: string): string | undefined {
  try {
    return localStorage.getItem(key) ?? undefined;
  } catch {
    return undefined;
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* 隐私模式等场景存储不可用:本轮会话内仍可工作 */
  }
}

export function NotificationWatcher() {
  const addNotification = useNotifications((s) => s.addNotification);

  useEffect(() => {
    if (!USE_BACKEND) return;
    let disposed = false;

    const poll = async () => {
      // ---- 1) 自动任务执行(定时触发由后端调度,页面无关) ----
      try {
        const execs = await automationsApi.listExecutions(20);
        if (disposed) return;
        const maxId = execs.reduce((m, e) => Math.max(m, e.id), 0);
        const baseline = readNum(EXEC_BASELINE_KEY);
        if (baseline == null) {
          // 首次(或清缓存后):只记基线,不为历史执行轰炸通知
          write(EXEC_BASELINE_KEY, String(maxId));
        } else if (maxId > baseline) {
          write(EXEC_BASELINE_KEY, String(maxId));
          // 旧→新依次通知(最新的最后入列,列表头自然最新在前)
          const fresh = execs.filter((e) => e.id > baseline).reverse();
          for (const e of fresh) {
            const ok = e.status === "success";
            addNotification(
              ok ? "任务执行完成" : "任务执行失败",
              `自动任务「${e.ruleName}」${ok ? "执行成功" : "执行失败"},耗时 ${e.duration}。`,
              ok ? "taskDone" : "taskFail",
            );
          }
        }
      } catch {
        /* 轮询失败静默,下轮重试 */
      }

      // ---- 2) PROC 守护事件(死亡/自愈/启动失败) ----
      try {
        const events = await environmentApi.procEvents(readStr(PROC_CURSOR_KEY));
        if (disposed) return;
        for (const ev of events) {
          write(PROC_CURSOR_KEY, ev.time);
          if (ev.type === "died" || ev.type === "start_failed") {
            addNotification(
              ev.type === "died" ? "托管进程自动恢复" : "托管进程启动失败",
              `${ev.name}:${ev.detail}`,
              "svcError",
            );
          }
        }
      } catch {
        /* 静默 */
      }
    };

    void poll();
    const timer = setInterval(() => { void poll(); }, 60_000);
    return () => {
      disposed = true;
      clearInterval(timer);
    };
  }, [addNotification]);

  return null;
}
