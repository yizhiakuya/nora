import { AlertTriangle, Brain, ChevronDown, Loader2, Terminal } from "lucide-react";
import { useState } from "react";
import type { ChatStep } from "@/lib/api/chatApi";

function outputLineCount(step: ChatStep): number | null {
  if (step.result?.lineCount != null) return step.result.lineCount;
  if (step.result?.content == null) return null;
  return step.result.content.split("\n", -1).length;
}

function argsPreview(step: ChatStep): string {
  if (!step.input) return "";
  const json = JSON.stringify(step.input);
  return json.length > 72 ? `${json.slice(0, 72)}…` : json;
}

function ToolDetail({ step }: { step: ChatStep }) {
  const inputJson = step.input ? JSON.stringify(step.input, null, 2) : null;
  const result = step.result;

  if (!inputJson && !result) {
    if (!step.detail) return <span className="text-muted-foreground">无附加信息</span>;
    const [args, legacy] = step.detail.split(/\n结果：([\s\S]*)/);
    return (
      <div className="space-y-1.5 w-full">
        <Section title="输入" content={args} />
        {legacy !== undefined && <Section title="输出" content={legacy} />}
      </div>
    );
  }

  return (
      <div className="space-y-1.5 w-full">
      {inputJson && <Section title="工具参数" content={inputJson} />}
      {result?.error ? (
        <Section title="失败原因" content={result.error} tone="error" />
      ) : result?.content != null ? (
        <Section
          title="执行结果"
          content={result.content}
          meta={
            <>
              {result.truncated && <span className="text-amber-600 dark:text-amber-400">· 已截断</span>}
              {step.toolName === "execute_sql" && result.rowCount != null && <span>· {result.rowCount} 行</span>}
            </>
          }
        />
      ) : null}
    </div>
  );
}

function Section({
  title,
  content,
  tone = "normal",
  meta,
}: {
  title: string;
  content: string;
  tone?: "normal" | "error";
  meta?: React.ReactNode;
}) {
  return (
    <div>
      <div className={`text-[10px] uppercase tracking-wide mb-0.5 flex items-center gap-1 ${tone === "error" ? "text-red-600 dark:text-red-400" : "text-muted-foreground"}`}>
        {tone === "error" && <AlertTriangle className="w-3 h-3" />}
        {tone !== "error" && <Terminal className="w-3 h-3" />}
        {title}
        {meta}
      </div>
      <pre
        className={`whitespace-pre-wrap break-words rounded-md px-2 py-1.5 text-[11px] font-mono max-h-48 overflow-auto ${
          tone === "error"
            ? "bg-red-50 dark:bg-red-950/30 text-red-700 dark:text-red-300"
            : "bg-muted/60 text-foreground"
        }`}
      >
        {content}
      </pre>
    </div>
  );
}

function StepBadge({ status }: { status: ChatStep["status"] }) {
  if (status === "running") {
    return (
      <span className="text-[10px] text-blue-600 dark:text-blue-400 flex items-center gap-1 shrink-0">
        <Loader2 className="w-3 h-3 animate-spin" />
        执行中
      </span>
    );
  }
  if (status === "failed") return <span className="text-[10px] text-red-600 dark:text-red-400 shrink-0">失败</span>;
  if (status === "declined") return <span className="text-[10px] text-amber-600 dark:text-amber-400 shrink-0">已拦截</span>;
  return null;
}

function statusDotClass(status: ChatStep["status"]): string {
  if (status === "running") return "bg-blue-500";
  if (status === "failed") return "bg-red-500";
  if (status === "declined") return "bg-amber-500";
  return "bg-green-500";
}

function ThoughtStepRow({ step, defaultOpen }: { step: ChatStep; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen || step.status === "running");
  const [userTouched, setUserTouched] = useState(false);
  const effectiveOpen = userTouched ? open : defaultOpen || step.status === "running";
  const expandable = Boolean(step.detail);
  return (
    <div>
      <button
        type="button"
        aria-expanded={effectiveOpen}
        onClick={() => {
          if (expandable) {
            setUserTouched(true);
            setOpen((v) => !v);
          }
        }}
        className={`w-full flex items-center gap-2 py-1 px-1.5 -mx-1.5 rounded-md text-left ${expandable ? "hover:bg-muted/70 cursor-pointer" : "cursor-default"}`}
      >
        <Brain className="w-3.5 h-3.5 text-violet-500 dark:text-violet-400 shrink-0" />
        <span className="text-xs text-foreground">{step.title || "思考过程"}</span>
        {expandable && <ChevronDown className={`w-3 h-3 text-muted-foreground/50 transition-transform ml-auto ${effectiveOpen ? "" : "-rotate-90"}`} />}
      </button>
      {effectiveOpen && expandable && (
        <div className="ml-6 my-1 border-l border-border pl-3">
          <pre className="whitespace-pre-wrap break-words text-[11px] leading-relaxed text-muted-foreground max-h-48 overflow-auto">
            {step.detail}
          </pre>
        </div>
      )}
    </div>
  );
}

function ToolStepRow({ step, defaultOpen }: { step: ChatStep; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const [userTouched, setUserTouched] = useState(false);
  const effectiveOpen = userTouched ? open : defaultOpen;
  const expandable = Boolean(step.input || step.result || step.detail);
  const lines = outputLineCount(step);
  const preview = argsPreview(step);

  return (
    <div className="animate-in fade-in slide-in-from-top-1">
      <button
        type="button"
        aria-expanded={effectiveOpen}
        onClick={() => {
          if (expandable) {
            setUserTouched(true);
            setOpen((v) => !v);
          }
        }}
        className={`w-full flex items-center gap-2 py-1.5 px-2 -mx-2 rounded-md text-left transition-colors ${expandable ? "hover:bg-muted/70 cursor-pointer" : "cursor-default"}`}
      >
        <span className={`w-2 h-2 rounded-full shrink-0 ${statusDotClass(step.status)}`} />
        <span className="text-xs font-medium text-foreground shrink-0">{step.toolName || step.title}</span>
        {step.toolName && preview && (
          <span className="text-[11px] text-muted-foreground font-mono truncate flex-1">{preview}</span>
        )}
        {!step.toolName && <span className="text-xs text-foreground truncate flex-1">{step.title}</span>}
        {step.status === "completed" && lines != null && (
          <span className="text-[10px] text-muted-foreground shrink-0 tabular-nums">{lines} lines of output</span>
        )}
        <StepBadge status={step.status} />
        {step.duration && <span className="text-[10px] text-muted-foreground/70 tabular-nums shrink-0">{step.duration}</span>}
        {expandable && <ChevronDown className={`w-3 h-3 text-muted-foreground/50 transition-transform shrink-0 ${effectiveOpen ? "" : "-rotate-90"}`} />}
      </button>
      {effectiveOpen && expandable && (
        <div className="ml-4 my-1 border-l border-border pl-3">
          <ToolDetail step={step} />
        </div>
      )}
    </div>
  );
}

function roundGroups(steps: ChatStep[]): Array<{ index: number; steps: ChatStep[] }> {
  const groups = new Map<number, ChatStep[]>();
  for (const step of steps) {
    const index = step.roundIndex ?? 1;
    const group = groups.get(index);
    if (group) group.push(step);
    else groups.set(index, [step]);
  }
  return Array.from(groups.entries())
    .sort(([a], [b]) => a - b)
    .map(([index, group]) => ({ index, steps: group }));
}

export function AgentThoughtBlock({
  steps,
  durationMs,
  expanded,
}: {
  steps: ChatStep[];
  durationMs?: number;
  expanded?: boolean;
}) {
  const runningCount = steps.filter((s) => s.status === "running").length;
  const failedCount = steps.filter((s) => s.status === "failed").length;
  const declinedCount = steps.filter((s) => s.status === "declined").length;
  const toolCount = steps.filter((s) => s.type === "tool").length;
  const isWorking = expanded || runningCount > 0;
  const defaultOpen = isWorking || failedCount > 0 || declinedCount > 0;
  const [open, setOpen] = useState(defaultOpen);
  const [userTouched, setUserTouched] = useState(false);
  const effectiveOpen = userTouched ? open : defaultOpen;

  return (
    <div className="rounded-xl border border-border bg-muted/25 animate-in fade-in slide-in-from-top-1 overflow-hidden">
      <button
        type="button"
        aria-expanded={effectiveOpen}
        onClick={() => {
          setUserTouched(true);
          setOpen((v) => !v);
        }}
        className="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 transition-colors cursor-pointer"
      >
        {isWorking ? (
          <Loader2 className="w-4 h-4 text-blue-500 animate-spin shrink-0" />
        ) : (
          <Brain className="w-4 h-4 text-violet-500 dark:text-violet-400 shrink-0" />
        )}
        <span className="text-sm font-medium text-foreground">{isWorking ? "思考中" : "思考完成"}</span>
        <span className="text-[11px] text-muted-foreground truncate flex-1">
          {toolCount > 0 && `${toolCount} 次工具调用`}
          {failedCount > 0 && `${toolCount > 0 ? " · " : ""}${failedCount} 失败`}
          {declinedCount > 0 && `${toolCount > 0 || failedCount > 0 ? " · " : ""}${declinedCount} 拦截`}
          {durationMs != null && `${toolCount > 0 || failedCount > 0 || declinedCount > 0 ? " · " : ""}${(durationMs / 1000).toFixed(1)}s`}
        </span>
        <ChevronDown className={`w-4 h-4 text-muted-foreground/60 transition-transform shrink-0 ${effectiveOpen ? "" : "-rotate-90"}`} />
      </button>

      {effectiveOpen && (
        <div className="px-3 pb-2.5 space-y-2">
          {roundGroups(steps).map((group, groupIdx) => (
            <div key={group.index} className={groupIdx > 0 ? "pt-2 border-t border-border/70" : ""}>
              <div className="space-y-0.5">
                {group.steps.map((step) =>
                  step.type === "think" ? (
                    <ThoughtStepRow
                      key={step.id || `${group.index}-${step.title}`}
                      step={step}
                      defaultOpen={true}
                    />
                  ) : (
                    <ToolStepRow
                      key={step.id || `${group.index}-${step.title}`}
                      step={step}
                      defaultOpen={step.status === "running" || step.status === "failed" || step.status === "declined"}
                    />
                  )
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
