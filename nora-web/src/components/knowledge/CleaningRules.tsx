'use client';

import { toast } from "sonner";
import { Switch } from "@/components/ui/switch";
import { PipelineRule } from "@/types";
import { usePreferences } from "@/hooks/usePreferences";

const CATEGORY_COLORS: Record<PipelineRule["category"], string> = {
  "格式": "bg-blue-100 dark:bg-blue-900/50 text-blue-700 dark:text-blue-300",
  "去噪": "bg-purple-100 dark:bg-purple-900/50 text-purple-700 dark:text-purple-300",
  "分块": "bg-green-100 dark:bg-green-900/50 text-green-700 dark:text-green-300",
  "安全": "bg-red-100 dark:bg-red-900/50 text-red-700 dark:text-red-300",
  "质量": "bg-yellow-100 dark:bg-yellow-900/50 text-yellow-700 dark:text-yellow-300",
};

export function CleaningRules() {
  const rules = usePreferences((s) => s.cleaningRules);
  const toggleRule = usePreferences((s) => s.toggleRule);

  const toggle = (id: number) => {
    toggleRule(id);
    const rule = rules.find((r) => r.id === id);
    if (rule) {
      toast.success(`规则「${rule.name}」已${rule.enabled ? "停用" : "启用"}`);
    }
  };

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      <div className="p-4 border-b border-border">
        <h3 className="text-sm font-bold text-foreground">清洗规则</h3>
        <p className="text-xs text-muted-foreground mt-0.5">
          数据进入索引前的自动化预处理流程，确保 chunk 质量。
        </p>
      </div>
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {rules.map((rule) => (
          <div key={rule.id} className="flex items-center justify-between p-4 hover:bg-muted/50 transition-colors">
            <div className="flex items-center gap-3 min-w-0">
              <span className={`inline-flex items-center px-2 py-0.5 rounded text-[10px] font-medium shrink-0 ${CATEGORY_COLORS[rule.category]}`}>
                {rule.category}
              </span>
              <div className="min-w-0">
                <div className={`text-sm font-medium ${rule.enabled ? "text-foreground" : "text-muted-foreground line-through"}`}>
                  {rule.name}
                </div>
                <div className="text-xs text-muted-foreground truncate">{rule.description}</div>
              </div>
            </div>
            <Switch checked={rule.enabled} onCheckedChange={() => toggle(rule.id)} />
          </div>
        ))}
      </div>
    </div>
  );
}
