'use client';

import { toast } from "sonner";
import { Play, Square, RotateCw, Container } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useServices } from "@/hooks/useServices";

const HEALTH_MAP = {
  healthy:  { label: "健康", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800",   dot: "bg-green-500 animate-pulse" },
  degraded: { label: "降级", cls: "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800", dot: "bg-yellow-500 animate-pulse" },
  down:     { label: "离线", cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",       dot: "bg-red-500" },
};

export function ServiceCards() {
  const services = useServices((s) => s.services);
  const toggleService = useServices((s) => s.toggleService);
  const restartService = useServices((s) => s.restartService);

  const toggle = (id: number) => {
    const { nextStatus, name } = toggleService(id);
    toast.success(`${name} 已${nextStatus === "running" ? "启动" : "停止"}`);
  };

  const restart = (id: number) => {
    const name = restartService(id);
    toast.success(`${name} 已发送重启信号`);
  };

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {services.map((svc) => {
        const h = HEALTH_MAP[svc.health];
        return (
          <div key={svc.id} className="bg-card border border-border rounded-xl p-4 flex flex-col justify-between">
            <div className="space-y-3">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2 min-w-0">
                  <Container className="w-4 h-4 text-blue-600 dark:text-blue-400 shrink-0" />
                  <span className="text-sm font-bold text-foreground font-mono truncate" title={svc.name}>{svc.name}</span>
                </div>
                <span className={`shrink-0 whitespace-nowrap inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold border ${h.cls}`}>
                  <span className={`w-1.5 h-1.5 rounded-full ${h.dot}`} /> {h.label}
                </span>
              </div>

              <div className="space-y-1.5 text-[11px] pb-2">
                <div className="flex justify-between items-center gap-2">
                  <span className="text-muted-foreground shrink-0">端口</span>
                  <span className="text-foreground font-mono tabular-nums truncate">:{svc.port}</span>
                </div>
                <div className="flex justify-between items-center gap-2">
                  <span className="text-muted-foreground shrink-0">运行时长</span>
                  <span className="text-foreground tabular-nums truncate">{svc.uptime}</span>
                </div>
                <div className="flex justify-between items-center gap-2">
                  <span className="text-muted-foreground shrink-0">CPU / 内存</span>
                  <span className="text-foreground tabular-nums truncate">{svc.cpu} / {svc.memory}</span>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-1.5 pt-2 mt-auto border-t border-border/50">
              {svc.status === "running" ? (
                <>
                  <Button variant="outline" size="sm" className="h-7 text-[10px] px-0 w-full" onClick={() => toggle(svc.id)}>
                    <Square className="w-3 h-3 mr-1" /> 停止
                  </Button>
                  <Button variant="outline" size="sm" className="h-7 text-[10px] px-0 w-full" onClick={() => restart(svc.id)}>
                    <RotateCw className="w-3 h-3 mr-1" /> 重启
                  </Button>
                </>
              ) : (
                <Button variant="outline" size="sm" className="col-span-2 h-7 text-[10px] px-0 w-full text-green-700 dark:text-green-300" onClick={() => toggle(svc.id)}>
                  <Play className="w-3 h-3 mr-1" /> 启动
                </Button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}