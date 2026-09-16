'use client';

import { Zap, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/custom/States";
import { useModelProviders, resolveDefaultProvider, type PerModelSettings } from "@/hooks/useModelProviders";
import { ReasoningLevelPicker } from "@/components/settings/model/ReasoningLevelConfig";
import { Switch } from "@/components/ui/switch";

/**
 * 模型列表 tab:按服务商分组的表格(实际请求模型 / 上下文窗口 / 思考等级)。
 * 每行的上下文窗口与思考等级即时保存(乐观更新 + PUT model_provider.modelSettings)。
 */
export function ModelsTab({ onAdd }: { onAdd: () => void }) {
  const providers = useModelProviders((s) => s.providers);
  const updateModelSettings = useModelProviders((s) => s.updateModelSettings);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const defaultProviderId = useModelProviders((s) => s.defaultProviderId);
  const enabledProviders = providers.filter((p) => p.enabled);
  const enabledModels = enabledProviders.flatMap((p) => p.models);
  // 默认标记只落在真实生效的「渠道 + 模型」组合上(同名模型跨渠道时只有一条)
  const activeProvider = resolveDefaultProvider(providers, defaultModel, defaultProviderId);

  if (enabledModels.length === 0) {
    return (
      <EmptyState
        icon={Zap}
        title="没有可用模型"
        description="先启用至少一个已连通的服务商，或接入新的服务商"
        action={
          <Button size="sm" onClick={onAdd}>
            <Plus className="w-4 h-4 mr-1.5" /> 接入服务商
          </Button>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      {/* 当前默认模型 */}
      <div className="bg-primary/5 dark:bg-primary/10 rounded-xl border border-primary/20 p-4 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
            <Zap className="w-4 h-4 text-primary" />
          </div>
          <div>
            <div className="text-xs text-muted-foreground">当前默认模型</div>
            <div className="text-sm font-bold text-foreground">
              {defaultModel}
              {activeProvider && <span className="text-xs font-normal text-muted-foreground ml-1.5">· {activeProvider.name}</span>}
            </div>
          </div>
        </div>
        <span className="text-[10px] text-muted-foreground">对话页与自动任务未单独指定时使用</span>
      </div>

      {enabledProviders.map((p) => (
        <div key={p.id} className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
          <div className="px-4 py-2.5 border-b border-border/60 bg-muted/30">
            <span className="text-xs font-bold text-foreground">{p.name}</span>
            <span className="text-[10px] text-muted-foreground ml-2">{p.models.length} 个模型</span>
          </div>
          {/* 表头 */}
          <div className="grid grid-cols-[1fr_110px_90px_220px] gap-3 px-4 py-2 border-b border-border/40 text-[10px] text-muted-foreground">
            <span>实际请求模型</span>
            <span>上下文窗口</span>
            <span title="开启后工具返回的图片会作为图像附件发给模型；自适应 = 先尝试，不支持时自动跳过">识图</span>
            <span>思考等级</span>
          </div>
          <div className="divide-y divide-border/40">
            {p.models.map((m) => {
              const settings: PerModelSettings = p.modelSettings?.[m] ?? {};
              const levels = settings.reasoningLevels ?? [];
              const defaultLevel = settings.defaultReasoningLevel ?? null;
              const setPatch = (patch: Partial<PerModelSettings>) => updateModelSettings(p.id, m, patch);
              return (
                <div key={m} className="grid grid-cols-[1fr_110px_90px_220px] gap-3 px-4 py-2 items-center hover:bg-muted/20 transition-colors">
                  <div className="min-w-0">
                    <span className="text-xs font-mono font-medium text-foreground truncate block" title={m}>{m}</span>
                    {m === defaultModel && activeProvider?.id === p.id && <span className="text-[10px] text-primary">默认</span>}
                  </div>
                  <input
                    type="number"
                    value={settings.contextWindow ?? ""}
                    placeholder="自动"
                    onChange={(e) => {
                      const raw = e.target.value;
                      setPatch({ contextWindow: raw === "" ? null : Number(raw) });
                    }}
                    className="h-8 w-full rounded-md border border-border bg-card px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-blue-500 transition-colors"
                  />
                  <div className="flex items-center gap-2">
                    <Switch
                      checked={settings.vision === true}
                      onCheckedChange={(checked) => setPatch({ vision: checked })}
                    />
                    <span className="text-[10px] text-muted-foreground">
                      {settings.vision === true ? "开" : settings.vision === false ? "关" : "自适应"}
                    </span>
                  </div>
                  <ReasoningLevelPicker
                    levels={levels}
                    defaultLevel={defaultLevel}
                    onLevelsChange={(next) => setPatch({ reasoningLevels: next })}
                    onDefaultChange={(next) => setPatch({ defaultReasoningLevel: next })}
                  />
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
