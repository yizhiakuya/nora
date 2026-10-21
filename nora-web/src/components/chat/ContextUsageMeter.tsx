import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import type { ContextMeter } from "@/lib/chatContext";
import { formatTokenCount } from "@/lib/tokenEstimate";

export function ContextUsageMeter({ meter }: { meter: ContextMeter }) {
  const percent = meter.limit > 0 ? Math.max(0, meter.tokens / meter.limit * 100) : 0;
  const label = meter.windowConfigured ? "上下文" : "预算";
  const percentage = `${meter.estimated ? "≈" : ""}${percent > 0 && percent < 1 ? "<1" : Math.round(percent)}%`;
  const usageLabel = `${label}已用 ${percentage}，${meter.tokens.toLocaleString()} / ${meter.limit.toLocaleString()} tokens`;
  const tone = percent >= 90 ? "text-destructive" : percent >= 75 ? "text-amber-600 dark:text-amber-400" : "text-muted-foreground";
  const description = [usageLabel,
    meter.usage?.source === "upstream" ? `最近请求完整输入 ${meter.usage.promptTokens} tokens（含缓存读取 ${meter.usage.cacheReadInputTokens ?? 0}、写入 ${meter.usage.cacheCreationInputTokens ?? 0}）`
      : "尚无上游输入计数，当前使用估算。",
    "正文与工具结果每秒估算增量；正在生成的思考不累加到输入占用。",
    meter.usage?.outputTokens != null ? `本次请求计费输出 ${meter.usage.outputTokens} tokens（含推理，不等于输入占用）` : "收到上游输入用量后校准。",
    meter.windowConfigured ? "按配置的模型窗口整理。" : `${formatTokenCount(meter.limit)} 仅为默认整理预算，不代表模型容量。`,
    "下一次请求前超过预算 83% 时自动整理，目标 65%。"];

  return <Popover>
    <PopoverTrigger asChild>
      <Button type="button" variant="ghost" size="icon" aria-label={`查看上下文用量：${usageLabel}`} title={description.join("\n")}
        className={`h-8 w-8 shrink-0 ${tone}`}>
        <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" role="meter"
          aria-label={`${label}占用`} aria-valuemin={0} aria-valuemax={100}
          aria-valuenow={Math.min(100, percent)} aria-valuetext={usageLabel}>
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="2" opacity="0.2" />
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="2" pathLength="100"
            strokeDasharray={`${Math.min(100, percent)} 100`} transform="rotate(-90 10 10)" />
        </svg>
      </Button>
    </PopoverTrigger>
    <PopoverContent align="start" collisionPadding={12} className="w-80 max-w-[calc(100vw-24px)] rounded-xl p-4" aria-label="上下文用量详情">
      <div className="mb-3 flex items-center justify-between gap-3">
        <h3 className="text-sm font-medium">{meter.windowConfigured ? "上下文窗口" : "上下文整理预算"}</h3>
        <span className={`text-sm tabular-nums ${tone}`}>{percentage}</span>
      </div>
      <meter className={`context-meter context-meter-detail w-full ${tone}`} min={0} max={100} low={75} high={90} optimum={0}
        value={Math.min(100, percent)} aria-label={`${label}占用详情`} aria-valuetext={usageLabel} />
      <p className="mt-2 text-xs tabular-nums">{meter.estimated ? "≈" : ""}{formatTokenCount(meter.tokens)} / {formatTokenCount(meter.limit)} tokens 已用</p>
      <div className="mt-3 space-y-2 border-t border-border/60 pt-3 text-[11px] leading-relaxed text-muted-foreground">
        {description.slice(1).map(line => <p key={line}>{line}</p>)}
      </div>
    </PopoverContent>
  </Popover>;
}
