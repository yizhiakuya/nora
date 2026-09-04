'use client';

import { toast } from "sonner";
import { Zap, Check, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/custom/States";
import { useModelProviders } from "@/hooks/useModelProviders";

export function ModelsTab({ onAdd }: { onAdd: () => void }) {
  const providers = useModelProviders((s) => s.providers);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const setDefaultModel = useModelProviders((s) => s.setDefaultModel);
  const enabledModels = providers.filter((p) => p.enabled).flatMap((p) => p.models);

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
            <div className="text-sm font-bold text-foreground">{defaultModel}</div>
          </div>
        </div>
        <span className="text-[10px] text-muted-foreground">对话页与自动任务未单独指定时使用</span>
      </div>

      {/* 按服务商分组列出模型 */}
      {providers.filter((p) => p.enabled).map((p) => (
        <div key={p.id} className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
          <div className="px-4 py-2.5 border-b border-border/60 bg-muted/30">
            <span className="text-xs font-bold text-foreground">{p.name}</span>
            <span className="text-[10px] text-muted-foreground ml-2">{p.models.length} 个模型</span>
          </div>
          <div className="divide-y divide-border/40">
            {p.models.map((m) => {
              const isDefault = defaultModel === m;
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => { setDefaultModel(m); toast.success(`默认模型已切换为 ${m}`); }}
                  className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-muted/40 transition-colors group"
                >
                  <div className="flex items-center gap-2.5 min-w-0">
                    <div className={`w-5 h-5 rounded-full border flex items-center justify-center shrink-0 transition-colors ${isDefault ? "bg-primary border-primary" : "border-border group-hover:border-primary/50"}`}>
                      {isDefault && <Check className="w-3 h-3 text-primary-foreground" />}
                    </div>
                    <span className="text-xs font-medium text-foreground truncate">{m}</span>
                    {isDefault && <span className="text-[10px] text-primary font-medium">默认</span>}
                  </div>
                  {!isDefault && <span className="text-[10px] text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity">设为默认</span>}
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
