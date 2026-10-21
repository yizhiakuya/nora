'use client';

import { Bell, CheckCheck, Trash2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useNotifications } from "@/hooks/useNotifications";
import { usePreferences } from "@/hooks/usePreferences";
import { Button } from "@/components/ui/button";
import { ResourceStatus } from "@/components/ui/custom/States";

export function NotificationBell() {
  const notifications = useNotifications((s) => s.notifications);
  const markAllRead = useNotifications((s) => s.markAllRead);
  const clearAll = useNotifications((s) => s.clearAll);
  const { writing, loading, syncError, syncFromBackend } = useNotifications();
  const inApp = usePreferences((s) => s.notifications.inApp);
  const unread = notifications.filter((n) => !n.read).length;

  if (!inApp) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="ghost" size="icon" className="relative h-8 w-8 text-muted-foreground"
          aria-label={unread ? `通知，${unread} 条未读` : "通知"} title={unread ? `通知 · ${unread} 条未读` : "通知"}>
          <Bell className="w-4 h-4" />
          {unread > 0 && (
            <span aria-hidden="true" className="absolute -top-0.5 -right-0.5 min-w-4 h-4 px-1 bg-destructive rounded-full border-2 border-background text-[9px] font-medium text-destructive-foreground flex items-center justify-center tabular-nums">
              {unread > 9 ? "9+" : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" collisionPadding={12} className="w-80 max-w-[calc(100vw-24px)] p-0">
        <div className="px-3 py-2 border-b border-border/60 flex items-center justify-between gap-2">
          <span className="text-[13px] font-medium shrink-0">通知{unread > 0 ? ` (${unread})` : ""}</span>
          <div className="flex items-center gap-0.5">
            <Button variant="ghost" size="sm" disabled={writing || unread === 0}
              type="button"
              onClick={markAllRead}
              className="h-7 px-2 text-[11px] text-muted-foreground"
            >
              <CheckCheck className="w-3 h-3" /> 全部已读
            </Button>
            {notifications.length > 0 && (
              <Button variant="ghost" size="sm" disabled={writing}
                type="button"
                onClick={clearAll}
                className="h-7 px-2 text-[11px] text-muted-foreground hover:text-destructive"
              >
                <Trash2 className="w-3 h-3" /> 清空
              </Button>
            )}
          </div>
        </div>
        <div className="max-h-[300px] overflow-y-auto custom-scroll">
          <ResourceStatus loading={loading} error={syncError} retry={syncFromBackend} />
          {notifications.length === 0 && (loading || syncError) ? null : notifications.length === 0 ? (
            <div className="flex flex-col items-center gap-2 px-6 py-9 text-xs text-muted-foreground"><Bell className="mb-1 h-5 w-5 opacity-60" /><span>暂无新通知</span><span className="text-[11px]">任务完成与需要关注的事项会显示在这里</span></div>
          ) : (
            notifications.map((n) => (
              <DropdownMenuItem key={n.id} className="p-3 flex items-start gap-3 border-b border-border/50 last:border-0 rounded-none">
                <div className={`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${n.read ? "bg-border" : "bg-primary"}`} />
                <div className="min-w-0">
                  <div className="text-xs font-medium text-foreground mb-1">{n.title}</div>
                  <div className="text-xs leading-relaxed text-muted-foreground line-clamp-2">{n.detail}</div>
                  <div className="text-[10px] text-muted-foreground mt-1.5 tabular-nums">{n.time}</div>
                </div>
              </DropdownMenuItem>
            ))
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
