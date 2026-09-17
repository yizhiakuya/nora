'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Server, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ServiceCards } from "@/components/environments/ServiceCards";
import { LogStream } from "@/components/environments/LogStream";
import { AddSourceModal } from "@/components/environments/AddSourceModal";
import { EmptyState } from "@/components/ui/custom/States";
import { Container } from "lucide-react";
import { useServices } from "@/hooks/useServices";
import { useNotifications } from "@/hooks/useNotifications";
import { USE_BACKEND } from "@/lib/api/client";
import { environmentApi } from "@/lib/services/environmentApi";

const TABS = ["服务", "日志"] as const;

/** 本会话已见过的最新守护事件时间(ISO);增量拉取去重用 */
let lastEventTime: string | null = null;

export default function EnvironmentsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("服务");
  const [addOpen, setAddOpen] = useState(false);
  const services = useServices((s) => s.services);
  const syncFromBackend = useServices((s) => s.syncFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);
  // 后端首次同步未完成前不渲染空态,避免「暂无纳管服务」闪现;
  // persist 已有上次服务列表时直接渲染(stale-while-revalidate:同步原地刷新),
  // 不再让骨架屏卡住首屏等慢同步
  const [loaded, setLoaded] = useState(!USE_BACKEND || services.length > 0);

  // 后端模式:进入页面拉一次真实容器/纳管源列表,之后 30s 轮询保持状态新鲜
  // (容器/进程在外部挂掉、PROC 崩溃自愈,卡片与 Header 指标实时联动);
  // 顺带增量拉 PROC 守护事件(死亡/自愈/启动失败)进通知中心
  useEffect(() => {
    if (!USE_BACKEND) return;
    const poll = async () => {
      await syncFromBackend().finally(() => setLoaded(true));
      try {
        const events = await environmentApi.procEvents(lastEventTime ?? undefined);
        for (const ev of events) {
          if (ev.type === "died" || ev.type === "start_failed") {
            addNotification(
              ev.type === "died" ? "托管进程自动恢复" : "托管进程启动失败",
              `${ev.name}:${ev.detail}`,
              "svcError"
            );
          }
          lastEventTime = ev.time;
        }
      } catch { /* 事件拉取失败不打断轮询 */ }
    };
    void poll();
    const timer = setInterval(() => { void poll(); }, 30_000);
    return () => clearInterval(timer);
  }, [syncFromBackend, addNotification]);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "环境控制台", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => setAddOpen(true)}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 添加纳管服务
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Server className="w-5 h-5 text-green-600 dark:text-green-400" /> 环境控制台
            </h1>
            <p className="text-xs text-muted-foreground mt-1">
              管理本地/测试环境服务，查看日志流，AI 自动诊断错误并给出修复建议。
            </p>
            <div role="tablist" aria-label="环境视图" className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit">
              {TABS.map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  aria-selected={tab === t}
                  onClick={() => setTab(t)}
                  className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${tab === t ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {tab === "服务" && (
              !loaded ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                  {[0, 1, 2, 3].map((i) => (
                    <div key={i} className="bg-card border border-border rounded-xl p-4 h-36 animate-pulse" />
                  ))}
                </div>
              ) : services.length === 0 ? (
                <EmptyState
                  icon={Container}
                  title="暂无纳管服务"
                  description={USE_BACKEND ? "点击右上角「添加纳管服务」接入第一个容器或日志文件" : "本地演示模式,启动后端后接入真实容器/日志"}
                />
              ) : (
                <>
                  <ServiceCards />
                  <LogStream />
                </>
              )
            )}
            {tab === "日志" && <LogStream />}
          </div>
          <AddSourceModal isOpen={addOpen} onClose={() => setAddOpen(false)} />
        </div>
      </div>
    </>
  );
}
