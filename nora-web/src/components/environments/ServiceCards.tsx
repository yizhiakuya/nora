'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Play, Square, RotateCw, Container, FileText, Trash2, Cog } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
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
  const removeManaged = useServices((s) => s.removeManaged);
  const activeServiceId = useServices((s) => s.activeServiceId);
  const setActiveService = useServices((s) => s.setActiveService);

  /** 待删除纳管源(确认弹窗);添加入口在页面 Header 的「添加纳管服务」按钮 */
  const [pendingRemove, setPendingRemove] = useState<{ id: number; name: string } | null>(null);
  const [removing, setRemoving] = useState(false);

  const toggle = (id: number) => {
    const { nextStatus, name } = toggleService(id);
    toast.success(`${name} 已${nextStatus === "running" ? "启动" : "停止"}`);
  };

  const restart = (id: number) => {
    const name = restartService(id);
    toast.success(`${name} 已发送重启信号`);
  };

  const confirmRemove = async () => {
    if (!pendingRemove) return;
    setRemoving(true);
    try {
      await removeManaged(pendingRemove.id);
      toast.success(`纳管源「${pendingRemove.name}」已移除`);
      setPendingRemove(null);
    } catch (e) {
      toast.error(`移除失败:${(e as Error).message}`);
    } finally {
      setRemoving(false);
    }
  };

  return (
    <>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {services.map((svc) => {
          const h = HEALTH_MAP[svc.health];
          const isFile = svc.kind === "FILE";
          const isProc = svc.kind === "PROC";
          return (
            <div
              key={svc.id}
              role="button"
              tabIndex={0}
              title="点击查看该服务日志"
              onClick={() => setActiveService(svc.id)}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setActiveService(svc.id); }}
              className={`bg-card border rounded-xl p-4 flex flex-col justify-between group relative hover:shadow-sm transition-all cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-blue-500/40 ${activeServiceId === svc.id ? "border-blue-500 dark:border-blue-400 ring-1 ring-blue-500/30 shadow-sm" : "border-border hover:border-border/80"}`}
            >
              <button
                type="button"
                title={`移除纳管源 ${svc.name}`}
                onClick={(e) => { e.stopPropagation(); setPendingRemove({ id: svc.id, name: svc.name }); }}
                className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity text-muted-foreground hover:text-red-500"
              >
                <Trash2 className="w-3 h-3" />
              </button>
              <div className="space-y-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-2 min-w-0">
                    {isFile
                      ? <FileText className="w-4 h-4 text-violet-600 dark:text-violet-400 shrink-0" />
                      : isProc
                        ? <Cog className="w-4 h-4 text-orange-600 dark:text-orange-400 shrink-0" />
                        : <Container className="w-4 h-4 text-blue-600 dark:text-blue-400 shrink-0" />}
                    <span className="text-sm font-bold text-foreground font-mono truncate" title={svc.name}>{svc.name}</span>
                  </div>
                  <span className={`shrink-0 whitespace-nowrap inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold border ${h.cls}`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${h.dot}`} /> {h.label}
                  </span>
                </div>

                <div className="space-y-1.5 text-[11px] pb-2">
                  <div className="flex justify-between items-center gap-2">
                    <span className="text-muted-foreground shrink-0">类型</span>
                    <span className="text-foreground tabular-nums truncate">{isFile ? "📄 进程日志" : isProc ? "⚙️ 托管程序" : "🐳 容器"}</span>
                  </div>
                  {isFile ? (
                    <div className="flex justify-between items-center gap-2">
                      <span className="text-muted-foreground shrink-0">日志状态</span>
                      <span className="text-foreground tabular-nums truncate" title={svc.fileLogPath}>{svc.uptime}</span>
                    </div>
                  ) : isProc ? (
                    <div className="flex justify-between items-center gap-2">
                      <span className="text-muted-foreground shrink-0">运行状态</span>
                      <span className="text-foreground tabular-nums truncate" title={svc.detail}>{svc.detail ?? svc.uptime}</span>
                    </div>
                  ) : (
                    <>
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
                    </>
                  )}
                </div>
              </div>

              {/* FILE 源:进程不由平台启停,只观测;DOCKER/PROC 源支持启停/重启(PROC 崩溃还会被守护自动拉起) */}
              {!isFile && (
                <div className="grid grid-cols-2 gap-1.5 pt-2 mt-auto border-t border-border/50">
                  {svc.status === "running" ? (
                    <>
                      <Button variant="outline" size="sm" className="h-7 text-[10px] px-0 w-full" onClick={(e) => { e.stopPropagation(); toggle(svc.id); }}>
                        <Square className="w-3 h-3 mr-1" /> 停止
                      </Button>
                      <Button variant="outline" size="sm" className="h-7 text-[10px] px-0 w-full" onClick={(e) => { e.stopPropagation(); restart(svc.id); }}>
                        <RotateCw className="w-3 h-3 mr-1" /> 重启
                      </Button>
                    </>
                  ) : (
                    <Button variant="outline" size="sm" className="col-span-2 h-7 text-[10px] px-0 w-full text-green-700 dark:text-green-300" onClick={(e) => { e.stopPropagation(); toggle(svc.id); }}>
                      <Play className="w-3 h-3 mr-1" /> 启动
                    </Button>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* 删除确认弹窗(破坏性操作,防误触) */}
      <Modal
        isOpen={!!pendingRemove}
        onClose={() => setPendingRemove(null)}
        title="移除纳管源"
        width="w-[380px]"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setPendingRemove(null)}>取消</Button>
            <Button size="sm" className="bg-red-600 hover:bg-red-700 dark:bg-red-500 dark:hover:bg-red-600" onClick={() => void confirmRemove()} disabled={removing}>
              {removing ? "移除中…" : "移除"}
            </Button>
          </>
        }
      >
        <p className="text-xs text-muted-foreground leading-relaxed">
          确定移除纳管源「<span className="font-bold text-foreground font-mono">{pendingRemove?.name}</span>」吗?
          仅解除平台纳管,不会停止或删除 {pendingRemove && (services.find((s) => s.id === pendingRemove.id)?.kind === "FILE" ? "日志文件" : services.find((s) => s.id === pendingRemove.id)?.kind === "PROC" ? "源记录与受管日志" : "容器")}本身。
        </p>
      </Modal>
    </>
  );
}
