'use client';

import { useState } from "react";
import { CheckCircle2, Bell, FileSearch, AlertTriangle, Monitor } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { usePreferences } from "@/hooks/usePreferences";
import { toast } from "sonner";

const EVENTS = [
  { key: "taskDone",   icon: Bell,        label: "任务执行完成", desc: "自动任务成功结束时通知" },
  { key: "taskFail",   icon: AlertTriangle, label: "任务执行失败", desc: "自动任务失败时通知（建议保持开启）" },
  { key: "indexed",    icon: FileSearch,  label: "文件索引入库", desc: "文件完成解析与向量化时通知" },
  { key: "svcError",   icon: AlertTriangle, label: "服务异常告警", desc: "环境服务出现 ERROR 级日志时通知" },
] as const;

export function NotificationSettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const notifications = usePreferences((s) => s.notifications);
  const setNotifications = usePreferences((s) => s.setNotifications);

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("通知偏好已保存");
    schedule(() => setSaved(false), 2000);
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Bell className="w-4 h-4 text-primary" />
            <label className="text-sm font-bold text-foreground">通知事件</label>
          </div>
          {EVENTS.map(({ key, icon: Icon, label, desc }) => (
            <div key={key} className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-muted flex items-center justify-center"><Icon className="w-4 h-4 text-foreground" /></div>
                <div>
                  <div className="text-sm font-medium text-foreground">{label}</div>
                  <div className="text-xs text-muted-foreground mt-0.5">{desc}</div>
                </div>
              </div>
              <Switch
                checked={notifications.events[key]}
                onCheckedChange={(v) =>
                  setNotifications({ events: { ...notifications.events, [key]: v } })
                }
              />
            </div>
          ))}
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Monitor className="w-4 h-4 text-primary" />
            <label className="text-sm font-bold text-foreground">通知通道</label>
          </div>
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium text-foreground">站内通知</div>
              <div className="text-xs text-muted-foreground mt-0.5">顶栏铃铛中心，未读角标提醒</div>
            </div>
            <Switch
              checked={notifications.inApp}
              onCheckedChange={(v) => setNotifications({ inApp: v })}
            />
          </div>
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium text-foreground">浏览器通知</div>
              <div className="text-xs text-muted-foreground mt-0.5">系统级推送（需授权）</div>
            </div>
            <Switch
              checked={notifications.browser}
              onCheckedChange={(v) => {
                setNotifications({ browser: v });
                if (v) toast.info("演示环境不申请系统通知权限");
              }}
            />
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end">
        <Button size="sm" className={`transition-colors ${saved ? "bg-green-600 dark:bg-green-500 text-white" : "bg-primary text-primary-foreground"}`} onClick={handleSave}>
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
