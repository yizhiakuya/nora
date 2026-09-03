'use client';

import { Bell, CheckCheck } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useNotifications } from "@/hooks/useNotifications";

export function NotificationBell() {
  const notifications = useNotifications((s) => s.notifications);
  const markAllRead = useNotifications((s) => s.markAllRead);
  const unread = notifications.filter((n) => !n.read).length;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <div className="relative cursor-pointer ml-1 sm:ml-2">
          <div className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-100 dark:hover:bg-gray-700 border border-transparent hover:border-gray-200 dark:hover:border-gray-800 transition-colors">
            <Bell className="w-4 h-4 text-gray-500 dark:text-gray-400" />
          </div>
          {unread > 0 && (
            <span className="absolute -top-0.5 -right-0.5 min-w-[16px] h-4 px-1 bg-red-500 rounded-full border border-white dark:border-gray-900 text-[9px] font-bold text-white flex items-center justify-center tabular-nums">
              {unread > 9 ? "9+" : unread}
            </span>
          )}
        </div>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 rounded-xl shadow-lg border-gray-200 dark:border-gray-800 p-0">
        <div className="bg-gray-50 dark:bg-gray-900 px-4 py-3 border-b border-gray-100 dark:border-gray-800 flex items-center justify-between rounded-t-xl">
          <span className="text-xs font-bold text-gray-700 dark:text-gray-200">系统通知{unread > 0 ? ` (${unread} 未读)` : ""}</span>
          <button
            type="button"
            onClick={markAllRead}
            className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer inline-flex items-center gap-1"
          >
            <CheckCheck className="w-3 h-3" /> 全部已读
          </button>
        </div>
        <div className="max-h-[300px] overflow-y-auto custom-scroll">
          {notifications.length === 0 ? (
            <div className="p-6 text-center text-xs text-gray-400 dark:text-gray-500">暂无通知</div>
          ) : (
            notifications.map((n) => (
              <DropdownMenuItem key={n.id} className="p-3 cursor-pointer flex items-start gap-3 border-b border-gray-50 dark:border-gray-800 rounded-none focus:bg-blue-50/50 dark:focus:bg-blue-950/30">
                <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${n.read ? "bg-gray-300 dark:bg-gray-600" : "bg-blue-500"}`} />
                <div className="min-w-0">
                  <div className="text-xs font-bold text-gray-800 dark:text-gray-100 mb-0.5">{n.title}</div>
                  <div className="text-[11px] text-gray-500 dark:text-gray-400 line-clamp-2">{n.detail}</div>
                  <div className="text-[9px] text-gray-400 dark:text-gray-500 mt-1">{n.time}</div>
                </div>
              </DropdownMenuItem>
            ))
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
