import { toast } from "sonner";
import { Play, Zap, Clock, Repeat, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { EmptyState } from "@/components/ui/custom/States";
import { useAutomations } from "@/hooks/useAutomations";

const STATUS_MAP = {
  // M0-03(2026-09-20):active 是"规则已启用"而非"正在执行"——原文案
  // 「运行中」让用户分不清规则启用与本次执行(审查报告 B08)。执行状态
  // 在「执行历史」视图里看。
  active: { label: "已启用", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300" },
  paused: { label: "已暂停", cls: "bg-muted text-muted-foreground" },
  error:  { label: "异常",   cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300" },
};

export function AutomationList() {
  const rules = useAutomations((s) => s.rules);
  const toggleRule = useAutomations((s) => s.toggleRule);
  const markRun = useAutomations((s) => s.markRun);
  const runningRuleIds = useAutomations((s) => s.runningRuleIds);
  const togglingRuleIds = useAutomations((s) => s.togglingRuleIds);

  const toggle = async (id: number) => {
    const ok = await toggleRule(id);
    const rule = useAutomations.getState().rules.find((r) => r.id === id);
    if (ok && rule) toast.success(`「${rule.name}」已${rule.enabled ? "启用" : "暂停"}`);
  };

  if (rules.length === 0) return <EmptyState icon={Zap} title="暂无定期任务" description="创建一个任务，让 Nora 按计划处理重复工作。" />;

  return (
    <div className="work-list">
      {rules.map((rule) => {
        const s = STATUS_MAP[rule.status];
        return (
          <article key={rule.id} className="px-4 py-4 sm:px-5 border-b border-border/60 last:border-0">
            <div className="flex items-center justify-between gap-3 mb-2">
              <div className="min-w-0 flex flex-wrap items-center gap-2">
                <span className={`text-[13px] font-medium break-words ${rule.enabled ? "text-foreground" : "text-muted-foreground"}`}>
                  {rule.name}
                </span>
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-md ${s.cls}`}>{s.label}</span>
                {/* M4-05:存量规则缺真实日程/连接 → 显示「需配置」而非正常启用 */}
                {rule.needsConfig && (
                  <span className="text-[10px] font-medium px-1.5 py-0.5 rounded-md bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-300" title="缺少真实日程/连接配置:请编辑补充后再启用">
                    需配置
                  </span>
                )}
              </div>
              <Switch aria-label={`启用任务：${rule.name}`} disabled={togglingRuleIds.includes(rule.id)} checked={rule.enabled} onCheckedChange={() => toggle(rule.id)} />
            </div>

            <div className="space-y-1.5 text-xs leading-relaxed">
              <p className="flex items-start gap-2 text-muted-foreground"><Repeat className="w-3.5 h-3.5 mt-0.5 shrink-0" /><span className="min-w-0 break-words"><span className="sr-only">触发条件：</span>{rule.trigger}</span></p>
              <p className="break-words">{rule.action}</p>
            </div>

            <div className="mt-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
                <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> 上次: {rule.lastRun}</span>
                {rule.nextRun && <span>下次: {rule.nextRun}</span>}
              </div>
              <Button variant="ghost" size="sm" className="h-8 text-xs px-2 text-muted-foreground" disabled={runningRuleIds.includes(rule.id)} onClick={() => void markRun(rule.id)}>
                {runningRuleIds.includes(rule.id) ? <Loader2 className="w-2.5 h-2.5 mr-0.5 animate-spin" /> : <Play className="w-2.5 h-2.5 mr-0.5" />}
                {runningRuleIds.includes(rule.id) ? "执行中…" : "立即运行"}
              </Button>
            </div>
          </article>
        );
      })}
    </div>
  );
}
