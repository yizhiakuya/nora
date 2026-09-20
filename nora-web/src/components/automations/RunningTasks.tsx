'use client';

import { useEffect, useState } from "react";
import { Loader2, Inbox, MessageSquare } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { fetchLiveTurn } from "@/lib/api/agentApi";
import { useChatSessions } from "@/hooks/useChatSessions";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 「正在处理」视图(M1,2026-09-20):聚合展示后台仍在运行的对话轮次。
 *
 * 数据来源:各会话的 /turn/live 探测(agent-service 的 TurnStreamRegistry
 * 为权威)。定期任务的运行中状态在「执行记录」里体现(manual 试跑同步完成,
 * 后台调度有记录),这里聚焦用户最常找的"我发出去还没回来看的任务"。
 *
 * 无运行中轮次时不展示"运行中"字样的假数据,给下一步引导。
 */
interface RunningEntry {
  sessionId: string;
  title: string;
  startedAtMs: number | null;
  bufferedEvents: number;
}

export function RunningTasks() {
  const navigate = useNavigate();
  const sessions = useChatSessions((s) => s.sessions);
  const setActive = useChatSessions((s) => s.setActive);
  const [entries, setEntries] = useState<RunningEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!USE_BACKEND) {
        setLoading(false);
        return;
      }
      const results: RunningEntry[] = [];
      // 并行探测最近会话(最多 10 个,避免大量请求)
      const recent = sessions.slice(0, 10);
      await Promise.all(recent.map(async (s) => {
        try {
          const info = await fetchLiveTurn(s.id);
          if (info?.running) {
            results.push({
              sessionId: s.id,
              title: s.title,
              startedAtMs: info.startedAtMs ?? null,
              bufferedEvents: info.bufferedEvents ?? 0,
            });
          }
        } catch {
          /* 单会话探测失败忽略 */
        }
      }));
      if (!cancelled) {
        setEntries(results);
        setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [sessions]);

  if (loading) {
    return (
      <div className="bg-card border border-border rounded-xl py-12 text-center text-xs text-muted-foreground flex items-center justify-center gap-2">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> 正在检查后台任务…
      </div>
    );
  }

  if (entries.length === 0) {
    return (
      <div className="bg-card border border-border rounded-xl py-16 text-center space-y-2">
        <Inbox className="w-8 h-8 mx-auto text-muted-foreground/40" />
        <div className="text-xs text-muted-foreground">当前没有正在处理的任务</div>
        <div className="text-[11px] text-muted-foreground/70">
          在「助手」中提出需求后,未完成的轮次会出现在这里;离开页面不会中断后台处理。
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {entries.map((e) => (
        <div key={e.sessionId} className="bg-card border border-border rounded-xl p-4 flex items-center gap-3">
          <Loader2 className="w-4 h-4 animate-spin text-blue-500 shrink-0" />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-bold text-foreground truncate">{e.title}</div>
            <div className="text-[11px] text-muted-foreground mt-0.5">
              处理中 · 已产生 {e.bufferedEvents} 个事件
              {e.startedAtMs ? ` · 开始于 ${new Date(e.startedAtMs).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}` : ""}
            </div>
          </div>
          <button
            type="button"
            onClick={() => {
              setActive(e.sessionId);
              navigate("/chat");
            }}
            className="shrink-0 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border border-border text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700 transition-colors cursor-pointer"
          >
            <MessageSquare className="w-3 h-3" /> 查看
          </button>
        </div>
      ))}
    </div>
  );
}
