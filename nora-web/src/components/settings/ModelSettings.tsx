'use client';

import { useState, useEffect, useMemo } from "react";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ProvidersTab } from "@/components/settings/model/ProvidersTab";
import { ModelsTab } from "@/components/settings/model/ModelsTab";
import { AddProviderDialog } from "@/components/settings/model/AddProviderDialog";
import { useModelProviders, resolveDefaultProvider, type ModelProvider } from "@/hooks/useModelProviders";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ResourceStatus } from "@/components/ui/custom/States";

export function ModelSettings() {
  const [tab, setTab] = useState("providers");
  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState<ModelProvider | null>(null);
  const providers = useModelProviders((s) => s.providers);
  const { loading, syncError } = useModelProviders();
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const defaultProviderId = useModelProviders((s) => s.defaultProviderId);
  const syncFromBackend = useModelProviders((s) => s.syncFromBackend);
  const enabledModels = useMemo(() => providers.filter((p) => p.enabled).flatMap((p) => p.models), [providers]);
  const connectedCount = providers.filter((p) => p.status === "ok").length;
  const activeProvider = resolveDefaultProvider(providers, defaultModel, defaultProviderId);

  // 支持 ?tab=models 深链
  useEffect(() => {
    const t = new URLSearchParams(window.location.search).get("tab");
    if (t === "models") setTab("models");
  }, []);

  // 后端模式:进入设置页拉一次服务端 provider 列表
  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

  const openAdd = () => { setEditing(null); setAddOpen(true); };
  const openEdit = (p: ModelProvider) => { setEditing(p); setAddOpen(true); };

  return (
    <div className="space-y-4">
      <ResourceStatus loading={loading} error={syncError} retry={syncFromBackend} />
      {/* 状态概览卡片 */}
      <div className="surface p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-foreground">模型接入</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              {providers.length > 0
                ? `${providers.length} 个服务商 · ${connectedCount} 个已验证连通 · ${enabledModels.length} 个可用模型 · 默认 ${defaultModel}${activeProvider ? `（${activeProvider.name}）` : ""}`
                : loading || syncError ? "等待服务商列表同步" : "尚未接入任何服务商，请先添加并测试连通性"}
            </p>
          </div>
          <Button size="sm" className="h-8 px-3 text-xs" onClick={openAdd}>
            <Plus className="w-3.5 h-3.5 mr-1" /> 接入服务商
          </Button>
        </div>
      </div>

      {/* Tab 切换：服务商 / 模型列表 */}
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList aria-label="模型配置视图">
          <TabsTrigger value="providers">
            服务商 ({providers.length})
          </TabsTrigger>
          <TabsTrigger value="models">
            模型列表 ({enabledModels.length})
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {!(providers.length === 0 && (loading || syncError)) && tab === "providers" && <ProvidersTab onAdd={openAdd} onEdit={openEdit} />}
      {!(providers.length === 0 && (loading || syncError)) && tab === "models" && <ModelsTab onAdd={openAdd} />}
      <AddProviderDialog isOpen={addOpen} onClose={() => setAddOpen(false)} editing={editing} />
    </div>
  );
}
