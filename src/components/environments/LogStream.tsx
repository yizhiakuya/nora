'use client';

import { useState } from "react";
import { Sparkles, Terminal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MOCK_LOGS, LogEntry } from "@/lib/devData";

const LEVEL_CLS: Record<LogEntry["level"], string> = {
  info:  "text-blue-600 dark:text-blue-400",
  warn:  "text-yellow-600 dark:text-yellow-400",
  error: "text-red-600 dark:text-red-400",
};

export function LogStream() {
  const [filter, setFilter] = useState<"all" | LogEntry["level"]>("all");
  const [selected, setSelected] = useState<LogEntry | null>(null);

  const filtered = MOCK_LOGS.filter((l) => filter === "all" || l.level === filter);

  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-950/50">
        <div className="flex items-center gap-1.5">
          <Terminal className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" />
          <span className="text-xs font-bold text-gray-700 dark:text-gray-200">日志流</span>
        </div>
        <div className="flex gap-1">
          {(["all", "info", "warn", "error"] as const).map((lv) => (
            <button
              key={lv}
              type="button"
              onClick={() => setFilter(lv)}
              className={`px-2 py-0.5 rounded text-[10px] font-medium cursor-pointer transition-colors ${filter === lv ? "bg-gray-800 dark:bg-gray-200 text-white dark:text-gray-900" : "bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-700"}`}
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
            onClick={() => setSelected(log)}
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
        <div className="border-t border-gray-100 dark:border-gray-800 p-3 bg-blue-50/50 dark:bg-blue-950/20 animate-in fade-in">
          <div className="flex items-start gap-2">
            <Sparkles className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400 shrink-0 mt-0.5" />
            <div className="min-w-0">
              <div className="text-xs font-bold text-gray-800 dark:text-gray-100 mb-1">AI 诊断</div>
              <p className="text-xs text-gray-600 dark:text-gray-300 leading-relaxed">
                该错误为 Redis 连接拒绝（ECONNREFUSED）。当前 <code className="font-mono text-red-600 dark:text-red-400">redis</code> 服务状态为「离线」。
                建议在上方服务卡片中点击「启动」恢复 Redis，或检查端口 6379 是否被占用。恢复后 api-gateway 会自动重连（最多重试 10 次，间隔 5s）。
              </p>
            </div>
          </div>
        </div>
      )}

      {!selected && (
        <div className="border-t border-gray-100 dark:border-gray-800 px-4 py-2 flex items-center justify-between">
          <span className="text-[10px] text-gray-400 dark:text-gray-500">点击日志行获取 AI 诊断</span>
          <Button variant="outline" size="sm" className="h-6 text-[10px] px-2" onClick={() => setSelected(MOCK_LOGS[0])}>
            <Sparkles className="w-2.5 h-2.5 mr-0.5" /> 诊断最新错误
          </Button>
        </div>
      )}
    </div>
  );
}
