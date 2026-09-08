import { AlertTriangle, Brain, Check, ChevronDown, Loader2, Wrench, Ban } from "lucide-react";
import { useState } from "react";
import type { ChatStep } from "@/lib/api/chatApi";

/**
 * Agent 过程时间线（内联式，无外框）：
 * - 推理（type=think）：浅色小字内联流式展示，结束后自动折叠为一行「已深度思考」
 * - 工具（type=tool）：单行紧凑 chip（名称 + 参数摘要 + 状态），可展开参数/结果
 * 整体作为消息骨架的一部分与回答平铺在同一时间线上。
 */

function outputLineCount(step: ChatStep): number | null {
  if (step.result?.lineCount != null) return step.result.lineCount;
  if (step.result?.content == null) return null;
  return step.result.content.split("\n", -1).length;
}

function argsPreview(step: ChatStep): string {
  if (step.toolName === "execute_sql" && step.input?.sql) {
    const sql = step.input.sql.replace(/\s+/g, " ").trim();
    return sql.length > 56 ? `${sql.slice(0, 56)}…` : sql;
  }
  if (!step.input) return "";
  const json = JSON.stringify(step.input);
  return json.length > 72 ? `${json.slice(0, 72)}…` : json;
}

/** 推理步骤：流式时展示浅色小字，结束后折叠为一行摘要（用户可手动展开回看）。 */
function ReasoningRow({ step }: { step: ChatStep }) {
  const running = step.status === "running";
  const [userOpen, setUserOpen] = useState<boolean | null>(null);
  // 流式中始终展开；结束后默认折叠,除非用户手动展开过
  const open = running ? true : (userOpen ?? false);
  const seconds = step.duration
    ? step.duration.replace(/s$/, "")
    : null;
  // 后端 s-error(模型轮失败)不是推理,渲染为独立错误行
  const isError = step.id === "s-error" || step.status === "failed";

  if (isError && step.status === "failed") {
    return (
      <div className="flex items-center gap-1.5 py-0.5 text-[11px] text-red-600 dark:text-red-400 animate-in fade-in slide-in-from-top-1">
        <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
        <span className="truncate">{step.detail || step.title || "模型轮失败"}</span>
      </div>
    );
  }

  return (
    <div className="animate-in fade-in slide-in-from-top-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setUserOpen(running ? !open : !(userOpen ?? false))}
        className="w-full flex items-center gap-1.5 py-0.5 -mx-1 px-1 rounded-md text-left hover:bg-muted/60 cursor-pointer transition-colors group"
      >
        {running ? (
          <Brain className="w-3.5 h-3.5 text-violet-500 dark:text-violet-400 animate-pulse shrink-0" />
        ) : (
          <Brain className="w-3.5 h-3.5 text-violet-400/70 dark:text-violet-500/70 shrink-0" />
        )}
        <span className="text-xs font-medium text-muted-foreground group-hover:text-foreground transition-colors">
          {running ? "思考中…" : "已深度思考"}
        </span>
        {!running && seconds && (
          <span className="text-[10px] text-muted-foreground/70 tabular-nums">{seconds}s</span>
        )}
        {step.status === "failed" && (
          <span className="text-[10px] text-red-500">· 失败</span>
        )}
        <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform ${open ? "" : "-rotate-90"}`} />
      </button>
      {open && step.detail && (
        <pre className="whitespace-pre-wrap break-words text-xs leading-relaxed text-muted-foreground border-l-2 border-violet-200 dark:border-violet-900 pl-3 ml-[6px] mt-1 mb-2 max-h-64 overflow-auto">
          {step.detail}
        </pre>
      )}
    </div>
  );
}

/** 工具步骤：单行 chip,可展开参数与结果详情。 */
function ToolRow({ step }: { step: ChatStep }) {
  const [open, setOpen] = useState(step.status === "running");
  const [userTouched, setUserTouched] = useState(false);
  const effectiveOpen = userTouched ? open : step.status === "running";
  const expandable = Boolean(step.input || step.result || step.detail);
  const lines = outputLineCount(step);
  const preview = argsPreview(step);
  const running = step.status === "running";

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
        className={`w-full flex items-center gap-2 py-1.5 px-2.5 rounded-lg text-left border transition-colors max-w-2xl ${
          expandable ? "hover:bg-muted/70 cursor-pointer" : "cursor-default"
        } ${
          step.status === "failed"
            ? "border-red-200 dark:border-red-900/60 bg-red-50/50 dark:bg-red-950/20"
            : step.status === "declined"
              ? "border-amber-200 dark:border-amber-900/60 bg-amber-50/50 dark:bg-amber-950/20"
              : "border-border bg-card"
        }`}
      >
        <span className="shrink-0">
          {running ? (
            <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />
          ) : step.status === "failed" ? (
            <AlertTriangle className="w-3.5 h-3.5 text-red-500" />
          ) : step.status === "declined" ? (
            <Ban className="w-3.5 h-3.5 text-amber-500" />
          ) : (
            <Check className="w-3.5 h-3.5 text-green-600 dark:text-green-500" />
          )}
        </span>
        <span className="text-xs font-medium text-foreground shrink-0 flex items-center gap-1">
          {!running && step.status === "completed" && <Wrench className="w-3 h-3 text-muted-foreground/60" />}
          {step.toolName || step.title}
        </span>
        {preview && (
          <span className="text-[11px] text-muted-foreground font-mono truncate flex-1">{preview}</span>
        )}
        {step.status === "completed" && lines != null && (
          <span className="text-[10px] text-muted-foreground/70 shrink-0 tabular-nums">{lines} 行</span>
        )}
        {step.status === "failed" && <span className="text-[10px] text-red-600 dark:text-red-400 shrink-0">失败</span>}
        {step.status === "declined" && <span className="text-[10px] text-amber-600 dark:text-amber-400 shrink-0">已拦截</span>}
        {step.duration && <span className="text-[10px] text-muted-foreground/60 tabular-nums shrink-0">{step.duration}</span>}
        {expandable && <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform shrink-0 ${effectiveOpen ? "" : "-rotate-90"}`} />}
      </button>
      {effectiveOpen && expandable && (
        <ToolDetail step={step} />
      )}
    </div>
  );
}

function ToolDetail({ step }: { step: ChatStep }) {
  const inputJson = step.input ? JSON.stringify(step.input, null, 2) : null;
  const result = step.result;

  if (!inputJson && !result) {
    if (!step.detail) return <span className="text-muted-foreground">无附加信息</span>;
    const [args, legacy] = step.detail.split(/\n结果：([\s\S]*)/);
    return (
      <div className="ml-6 my-1 space-y-1.5 max-w-2xl">
        <Section title="输入" content={args} />
        {legacy !== undefined && <Section title="输出" content={legacy} />}
      </div>
    );
  }

  return (
    <div className="ml-6 my-1 space-y-1.5 max-w-2xl">
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

/** 单条步骤（think/tool 分发）。 */
function StepRow({ step }: { step: ChatStep }) {
  return step.type === "think" ? <ReasoningRow step={step} /> : <ToolRow step={step} />;
}

/**
 * Agent 过程时间线：不再渲染为带标题的"思考块盒子"，
 * 而是与回答平铺的内联步骤列表；元信息（工具次数/tokens/耗时）
 * 由 ChatMessageItem 放到回答下方的 TurnMeta 行。
 */
export function AgentThoughtBlock({ steps }: { steps: ChatStep[] }) {
  if (steps.length === 0) return null;
  return (
    <div className="space-y-1">
      {steps.map((step) => (
        <StepRow key={step.id || `${step.type}-${step.title}`} step={step} />
      ))}
    </div>
  );
}

/**
 * 回答下方的执行元信息行(灰色小字):N 次工具调用 · N tokens · Ns。
 * 仅在轮次结束后展示。
 */
export function TurnMeta({
  steps,
  durationMs,
  usage,
  ttftMs,
}: {
  steps?: ChatStep[];
  durationMs?: number;
  usage?: { inputTokens?: number; outputTokens?: number; totalTokens?: number } | null;
  ttftMs?: number | null;
}) {
  const toolCount = steps?.filter((s) => s.type === "tool").length ?? 0;
  const tokens = usage?.totalTokens ?? usage?.outputTokens ?? null;

  if (toolCount === 0 && tokens == null && durationMs == null) return null;

  const parts: string[] = [];
  if (toolCount > 0) parts.push(`${toolCount} 次工具调用`);
  if (tokens != null) parts.push(`${tokens} tokens`);
  if (ttftMs != null && ttftMs >= 0) parts.push(`首字 ${(ttftMs / 1000).toFixed(1)}s`);
  if (durationMs != null) parts.push(`${(durationMs / 1000).toFixed(1)}s`);

  return (
    <div className="text-[10px] text-muted-foreground/70 tabular-nums pt-0.5">{parts.join(" · ")}</div>
  );
}
