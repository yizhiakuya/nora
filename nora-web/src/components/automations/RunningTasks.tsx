'use client';

import { useEffect, useState } from "react";
import { Loader2, Inbox, MessageSquare, AlertTriangle } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { fetchChatRuns, type ChatRunInfo } from "@/lib/api/agentApi";
import { useChatSessions } from "@/hooks/useChatSessions";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 「正在处理」视图(M3-01,2026-09-20,方案 §4.3):
 * 后端持久化的对话运行(chat_run)聚合——刷新/换页/进程重启后都能找到
 * 「我发出去还没回来看的任务」。
 *
 * 状态语义(方案 §6.3):running/queued/awaiting_approval/cancelling 为进行中;
 * interrupted(进程中断)单独列出,给「查看已记录内容」入口而不是假装完成。
 */
const ACTIVE_STATUSES = ["queued", "running", "awaiting_approval", "cancelling"];

const STATUS_LABEL: Record<string, string> = {
  queued: "已接受,等待开始",
  running: "处理中",
  awaiting_approval: "等待确认",
  cancelling: "正在停止",
  interrupted: "已中断(进程重启,需手动决定是否重试)",
};

export function RunningTasks() {
  const navigate = useNavigate();
  const setActive = useChatSessions((s) => s.setActive);
  const [runs, setRuns] = useState<ChatRunInfo[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!USE_BACKEND) {
        setLoading(false);
        return;
      }
      try {
        // 进行中 + 中断(单独呈现)——均属"需要用户关注"的运行
        const items = await fetchChatRuns([...ACTIVE_STATUSES, "interrupted"], 30);
        if (!cancelled) setRuns(items);
      } catch {
        /* 后端不可达:空态 */
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div className="bg-card border border-border rounded-xl py-12 text-center text-xs text-muted-foreground flex items-center justify-center gap-2">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> 正在检查后台任务…
      </div>
    );
  }

  if (runs.length === 0) {
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
      {runs.map((r) => {
        const interrupted = r.status === "interrupted";
        const Icon = interrupted ? AlertTriangle : Loader2;
        return (
          <div key={r.id} className="bg-card border border-border rounded-xl p-4 flex items-center gap-3">
            <Icon className={`w-4 h-4 shrink-0 ${interrupted ? "text-amber-500" : "animate-spin text-blue-500"}`} />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-bold text-foreground truncate">
                {r.sessionTitle || r.content?.slice(0, 30) || r.sessionId}
              </div>
              <div className="text-[11px] text-muted-foreground mt-0.5">
                {STATUS_LABEL[r.status] ?? r.status}
                {r.startedAt ? ` · 开始于 ${r.startedAt.slice(11, 16)}` : ""}
              </div>
            </div>
            <button
              type="button"
              onClick={() => {
                setActive(r.sessionId);
                navigate("/chat");
              }}
              className="shrink-0 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border border-border text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700 transition-colors cursor-pointer"
            >
              <MessageSquare className="w-3 h-3" /> 查看
            </button>
          </div>
        );
      })}
    </div>
  );
}
