'use client';

import { useEffect, useState } from "react";
import { Sparkles, Terminal, Zap, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { LogEntry } from "@/types";
import { useServices } from "@/hooks/useServices";
import { useAutomations } from "@/hooks/useAutomations";
import { useNotifications } from "@/hooks/useNotifications";
import { subscribeLogs } from "@/lib/services/environmentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { toast } from "sonner";

const LEVEL_CLS: Record<LogEntry["level"], string> = {
  info:  "text-blue-600 dark:text-blue-400",
  warn:  "text-yellow-600 dark:text-yellow-400",
  error: "text-red-600 dark:text-red-400",
};

export function LogStream() {
  const [filter, setFilter] = useState<"all" | LogEntry["level"]>("all");
  const [selected, setSelected] = useState<LogEntry | null>(null);
  const [taskCreated, setTaskCreated] = useState(false);
  const [notifiedError, setNotifiedError] = useState(false);
  const addRule = useAutomations((s) => s.addRule);
  const addNotification = useNotifications((s) => s.addNotification);
  const ingestDockerLog = useServices((s) => s.ingestDockerLog);
  const services = useServices((s) => s.services);

  // 后端模式:订阅第一个运行中容器的真实日志 SSE
  useEffect(() => {
    if (!USE_BACKEND) return;
    const target = services.find((s) => s.status === "running");
    if (!target) return;
    const cancel = subscribeLogs(
      target.name,
      (line) => {
        if (line.trim()) ingestDockerLog(target.name, line);
      },
      () => { /* 后端不可用,沿用本地日志 */ }
    );
    return cancel;
  }, [services, ingestDockerLog]);

  const handleSelect = (log: LogEntry) => {
    setSelected(log);
    // 服务异常告警事件：选中 ERROR 级日志时产生，受「通知偏好 → 事件开关」过滤。
    if (log.level === "error" && !notifiedError) {
      setNotifiedError(true);
      addNotification(
        "服务异常告警",
        `${log.service} 出现 ERROR 级日志：${log.message}`,
        "svcError"
      );
    }
  };

  const createFixTask = () => {
    if (taskCreated) return;
    addRule(
      "修复任务：恢复 Redis 服务",
      "手动触发",
      "启动 redis 容器 → 等待健康检查通过 → 观察 api-gateway 重连日志 5 分钟",
    );
    setTaskCreated(true);
    toast.success("修复任务已创建，到「自动任务」页运行");
  };

  const logs = useServices((s) => s.logs);
  const filtered = logs.filter((l) => filter === "all" || l.level === filter);

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50">
        <div className="flex items-center gap-1.5">
          <Terminal className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-bold text-foreground">日志流</span>
        </div>
        <div className="flex gap-1">
          {(["all", "info", "warn", "error"] as const).map((lv) => (
            <button
              key={lv}
              type="button"
              onClick={() => setFilter(lv)}
              className={`px-2 py-0.5 rounded text-[10px] font-medium cursor-pointer transition-colors ${filter === lv ? "bg-gray-800 dark:bg-gray-200 text-white dark:text-gray-900" : "bg-muted text-muted-foreground hover:bg-gray-200 dark:hover:bg-gray-700"}`}
            >
              {lv === "all" ? "全部" : lv.toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      <div className="bg-[#1e1e1e] font-mono text-xs leading-relaxed max-h-64 overflow-y-auto custom-scroll p-3 space-y-0.5">
        {filtered.map((log, i) => (
          <div
            key={i}
            onClick={() => handleSelect(log)}
            className={`flex gap-2 px-1 py-0.5 rounded cursor-pointer transition-colors ${selected?.message === log.message ? "bg-blue-900/30" : "hover:bg-white/5"}`}
          >
            <span className="text-gray-500 shrink-0 tabular-nums">{log.time}</span>
            <span className={`${LEVEL_CLS[log.level]} font-bold shrink-0 uppercase w-10`}>{log.level}</span>
            <span className="text-purple-400 shrink-0">{log.service}</span>
            <span className="text-gray-300">{log.message}</span>
          </div>
        ))}
      </div>

      {selected && (
        <div className="border-t border-border p-3 bg-blue-50/50 dark:bg-blue-950/20 animate-in fade-in">
          <div className="flex items-start gap-2">
            <Sparkles className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400 shrink-0 mt-0.5" />
            <div className="min-w-0">
              <div className="text-xs font-bold text-foreground mb-1">AI 诊断</div>
              <p className="text-xs text-muted-foreground leading-relaxed">
                该错误为 Redis 连接拒绝（ECONNREFUSED）。当前 <code className="font-mono text-red-600 dark:text-red-400">redis</code> 服务状态为「离线」。
                建议在上方服务卡片中点击「启动」恢复 Redis，或检查端口 6379 是否被占用。恢复后 api-gateway 会自动重连（最多重试 10 次，间隔 5s）。
              </p>
              <button
                type="button"
                onClick={createFixTask}
                className={`mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${taskCreated ? "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800" : "bg-card text-muted-foreground border-border hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700"}`}
              >
                {taskCreated ? <Check className="w-3 h-3" /> : <Zap className="w-3 h-3" />}
                {taskCreated ? "修复任务已创建" : "创建修复任务"}
              </button>
            </div>
          </div>
        </div>
      )}

      {!selected && (
        <div className="border-t border-border px-4 py-2 flex items-center justify-between">
          <span className="text-[10px] text-muted-foreground">点击日志行获取 AI 诊断</span>
          <Button variant="outline" size="sm" className="h-6 text-[10px] px-2" disabled>
            <Sparkles className="w-2.5 h-2.5 mr-0.5" /> 诊断最新错误
          </Button>
        </div>
      )}
    </div>
  );
}
