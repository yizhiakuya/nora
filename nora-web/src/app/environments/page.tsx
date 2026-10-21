'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Server, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ServiceCards } from "@/components/environments/ServiceCards";
import { LogStream } from "@/components/environments/LogStream";
import { AddSourceModal } from "@/components/environments/AddSourceModal";
import { EmptyState, ResourceStatus } from "@/components/ui/custom/States";
import { usePollingResource } from "@/hooks/usePollingResource";
import { Container } from "lucide-react";
import { useServices } from "@/hooks/useServices";

const TABS = ["服务", "日志"] as const;

export default function EnvironmentsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("服务");
  const [addOpen, setAddOpen] = useState(false);
  const services = useServices((s) => s.services);
  const syncFromBackend = useServices((s) => s.syncFromBackend);
  const { loading, syncError } = useServices();
  usePollingResource(syncFromBackend);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "环境控制台", isCurrent: true }]}
        actions={
          <Button size="sm" onClick={() => setAddOpen(true)}>
            <Plus className="w-4 h-4" /> 添加纳管服务
          </Button>
        }
      />

      <div className="page-content custom-scroll">
        <Tabs value={tab} onValueChange={(value) => setTab(value as (typeof TABS)[number])} className="page-container">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="page-heading flex items-center gap-2.5">
              <Server className="w-5 h-5 text-muted-foreground" /> 环境控制台
            </h1>
            <p className="page-description">
              管理本地/测试环境服务，查看日志流，AI 自动诊断错误并给出修复建议。
            </p>
            {services[0]?.observer && (
              <p className="text-xs text-muted-foreground">
                观察位置：{services[0].observer.host} · {services[0].observer.platform}
                {services[0].observer.scope === "container" ? " · 容器内" : " · 主机"}
              </p>
            )}
            <TabsList aria-label="环境视图">
              {TABS.map((view) => <TabsTrigger key={view} value={view}>{view}</TabsTrigger>)}
            </TabsList>
          </div>

          <TabsContent value={tab} className="space-y-6">
            <ResourceStatus loading={loading} error={syncError} retry={syncFromBackend} />
            {tab === "服务" && (
              services.length === 0 && loading && !syncError ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                  {[0, 1, 2, 3].map((i) => (
                    <div key={i} className="bg-card border border-border rounded-xl p-4 h-36 animate-pulse" />
                  ))}
                </div>
              ) : services.length === 0 && syncError ? null : services.length === 0 ? (
                <EmptyState
                  icon={Container}
                  title="暂无纳管服务"
                  description="点击右上角「添加纳管服务」接入第一个容器或日志文件"
                />
              ) : (
                <>
                  <ServiceCards />
                  <LogStream />
                </>
              )
            )}
            {tab === "日志" && <LogStream />}
          </TabsContent>
          <AddSourceModal isOpen={addOpen} onClose={() => setAddOpen(false)} />
        </Tabs>
      </div>
    </>
  );
}
