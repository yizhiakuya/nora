'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Play, Square, RotateCw, Container } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MOCK_SERVICES, ServiceInstance } from "@/lib/devData";

const HEALTH_MAP = {
  healthy:  { label: "健康", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800",   dot: "bg-green-500 animate-pulse" },
  degraded: { label: "降级", cls: "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800", dot: "bg-yellow-500 animate-pulse" },
  down:     { label: "离线", cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",       dot: "bg-red-500" },
};

export function ServiceCards() {
  const [services, setServices] = useState<ServiceInstance[]>(MOCK_SERVICES);

  const toggle = (id: number) => {
    setServices((prev) =>
      prev.map((s) => {
        if (s.id !== id) return s;
        const next = s.status === "running" ? "stopped" : "running";
        toast.success(`${s.name} 已${next === "running" ? "启动" : "停止"}`);
        return { ...s, status: next, health: next === "running" ? "healthy" : "down", uptime: next === "running" ? "刚刚" : "—" };
      })
    );
  };

  const restart = (id: number) => {
    setServices((prev) => prev.map((s) => (s.id === id ? { ...s, uptime: "刚刚" } : s)));
    toast.success("已发送重启信号");
  };

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {services.map((svc) => {
        const h = HEALTH_MAP[svc.health];
        return (
          <div key={svc.id} className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl p-4 space-y-3">
            <div className="flex items-start justify-between">
              <div className="flex items-center gap-2">
                <Container className="w-4 h-4 text-blue-600 dark:text-blue-400" />
                <span className="text-sm font-bold text-gray-800 dark:text-gray-100 font-mono">{svc.name}</span>
              </div>
              <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold border ${h.cls}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${h.dot}`} /> {h.label}
              </span>
            </div>

            <div className="space-y-1 text-[11px]">
              <div className="flex justify-between">
                <span className="text-gray-400 dark:text-gray-500">端口</span>
                <span className="text-gray-800 dark:text-gray-100 font-mono tabular-nums">:{svc.port}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-400 dark:text-gray-500">运行时长</span>
                <span className="text-gray-800 dark:text-gray-100 tabular-nums">{svc.uptime}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-400 dark:text-gray-500">CPU / 内存</span>
                <span className="text-gray-800 dark:text-gray-100 tabular-nums">{svc.cpu} / {svc.memory}</span>
              </div>
            </div>

            <div className="flex gap-1.5 pt-1">
              {svc.status === "running" ? (
                <>
                  <Button variant="outline" size="sm" className="h-6 text-[10px] px-2 flex-1" onClick={() => toggle(svc.id)}>
                    <Square className="w-2.5 h-2.5 mr-0.5" /> 停止
                  </Button>
                  <Button variant="outline" size="sm" className="h-6 text-[10px] px-2 flex-1" onClick={() => restart(svc.id)}>
                    <RotateCw className="w-2.5 h-2.5 mr-0.5" /> 重启
                  </Button>
                </>
              ) : (
                <Button variant="outline" size="sm" className="h-6 text-[10px] px-2 flex-1 text-green-700 dark:text-green-300" onClick={() => toggle(svc.id)}>
                  <Play className="w-2.5 h-2.5 mr-0.5" /> 启动
                </Button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
