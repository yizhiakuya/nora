import { toast } from "sonner";
import { Play, Zap, Clock, Repeat } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { AutomationRule } from "@/lib/devData";
import { useAutomations } from "@/hooks/useAutomations";

const STATUS_MAP = {
  active: { label: "运行中", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300" },
  paused: { label: "已暂停", cls: "bg-muted text-muted-foreground" },
  error:  { label: "异常",   cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300" },
};

export function AutomationList() {
  const rules = useAutomations((s) => s.rules);
  const toggleRule = useAutomations((s) => s.toggleRule);
  const markRun = useAutomations((s) => s.markRun);

  const toggle = (id: number) => {
    const rule = rules.find((r) => r.id === id);
    toggleRule(id);
    if (rule) toast.success(`「${rule.name}」已${rule.enabled ? "暂停" : "启用"}`);
  };

  const runNow = (rule: AutomationRule) => {
    markRun(rule.id);
    toast.success(`「${rule.name}」已触发`);
  };

  return (
    <div className="space-y-3">
      {rules.map((rule) => {
        const s = STATUS_MAP[rule.status];
        return (
          <div key={rule.id} className="bg-card border border-border rounded-xl p-4">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Zap className={`w-4 h-4 ${rule.enabled ? "text-yellow-500" : "text-muted-foreground/60"}`} />
                <span className={`text-sm font-bold ${rule.enabled ? "text-foreground" : "text-muted-foreground"}`}>
                  {rule.name}
                </span>
                <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded-full ${s.cls}`}>{s.label}</span>
              </div>
              <Switch checked={rule.enabled} onCheckedChange={() => toggle(rule.id)} />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs mb-3">
              <div className="flex items-start gap-1.5 p-2 bg-muted rounded-lg">
                <Repeat className="w-3 h-3 text-blue-500 mt-0.5 shrink-0" />
                <div>
                  <div className="text-[9px] text-muted-foreground uppercase tracking-wide">触发条件</div>
                  <div className="text-foreground">{rule.trigger}</div>
                </div>
              </div>
              <div className="flex items-start gap-1.5 p-2 bg-muted rounded-lg">
                <Play className="w-3 h-3 text-purple-500 mt-0.5 shrink-0" />
                <div>
                  <div className="text-[9px] text-muted-foreground uppercase tracking-wide">执行动作</div>
                  <div className="text-foreground font-mono">{rule.action}</div>
                </div>
              </div>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3 text-[10px] text-muted-foreground">
                <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> 上次: {rule.lastRun}</span>
                {rule.nextRun && <span>下次: {rule.nextRun}</span>}
              </div>
              <Button variant="outline" size="sm" className="h-6 text-[10px] px-2" onClick={() => runNow(rule)}>
                <Play className="w-2.5 h-2.5 mr-0.5" /> 立即运行
              </Button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
