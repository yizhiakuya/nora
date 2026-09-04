'use client';

import { useState, useEffect, useMemo } from "react";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ProvidersTab } from "@/components/settings/model/ProvidersTab";
import { ModelsTab } from "@/components/settings/model/ModelsTab";
import { AddProviderDialog } from "@/components/settings/model/AddProviderDialog";
import { useModelProviders } from "@/hooks/useModelProviders";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";

export function ModelSettings() {
  const [tab, setTab] = useState("providers");
  const [addOpen, setAddOpen] = useState(false);
  const providers = useModelProviders((s) => s.providers);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const enabledModels = useMemo(() => providers.filter((p) => p.enabled).flatMap((p) => p.models), [providers]);
  const connectedCount = providers.filter((p) => p.status === "ok").length;

  // 支持 ?tab=models 深链
  useEffect(() => {
    const t = new URLSearchParams(window.location.search).get("tab");
    if (t === "models") setTab("models");
  }, []);

  return (
    <div className="space-y-4">
      {/* 状态概览卡片 */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-bold text-foreground">模型接入</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              {connectedCount > 0
                ? `${connectedCount} 个服务商已连通 · ${enabledModels.length} 个可用模型 · 默认 ${defaultModel}`
                : "尚未接入任何服务商，请先添加并测试连通性"}
            </p>
          </div>
          <Button size="sm" className="h-8 px-3 text-xs" onClick={() => setAddOpen(true)}>
            <Plus className="w-3.5 h-3.5 mr-1" /> 接入服务商
          </Button>
        </div>
      </div>

      {/* Tab 切换：服务商 / 模型列表 */}
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="bg-muted/50 dark:bg-muted/30 p-1 h-auto">
          <TabsTrigger value="providers" className="text-xs px-3 py-1.5 data-[state=active]:bg-card dark:data-[state=active]:bg-card data-[state=active]:shadow-sm rounded-md">
            服务商 ({providers.length})
          </TabsTrigger>
          <TabsTrigger value="models" className="text-xs px-3 py-1.5 data-[state=active]:bg-card dark:data-[state=active]:bg-card data-[state=active]:shadow-sm rounded-md">
            模型列表 ({enabledModels.length})
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {tab === "providers" && <ProvidersTab onAdd={() => setAddOpen(true)} />}
      {tab === "models" && <ModelsTab onAdd={() => setAddOpen(true)} />}
      <AddProviderDialog isOpen={addOpen} onClose={() => setAddOpen(false)} />
    </div>
  );
}
