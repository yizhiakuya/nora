import { AlertTriangle, Brain, ChevronDown, ChevronRight, Info, Loader2 } from "lucide-react";
import { useState } from "react";
import type { ChatStep } from "@/lib/api/chatApi";
import { ArtifactsBlock } from "./galleries";
import { parseArtifactsFence, parseLegacyGalleryFence, type ArtifactGallery } from "@/lib/artifacts";
import { ToolRow } from "./ToolStepRow";
import { ContextRow } from "./ContextStepRow";

/**
 * Agent 过程时间线（内联式，无外框）：
 * - 推理（type=think）：浅色小字内联流式展示，结束后自动折叠为一行「已深度思考」
 * - 工具（type=tool）：单行紧凑 chip（名称 + 参数摘要 + 状态），可展开参数/结果
 * 整体作为消息骨架的一部分与回答平铺在同一时间线上。
 */





/** 推理步骤：默认折叠为一行摘要(流式中也是),点击展开回看。 */
function ReasoningRow({ step }: { step: ChatStep }) {
  const running = step.status === "running";
  const [userOpen, setUserOpen] = useState<boolean | null>(null);
  // 默认折叠;用户点击永远优先。流式中折叠不丢内容,delta 仍会累积进 detail,
  // 展开即见全文
  const open = userOpen ?? false;
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

  // 通知型 think 步骤(s-effort 档位降级 / s-vision 不识图 / s-compact 上下文压缩等):
  // 标题即语义,渲染为中性通知行——此前一律按推理行显示成「已深度思考 0.00s」,
  // 与真实含义相反,降级说明还被折叠隐藏。
  if (!step.id.startsWith("s-reasoning-")) {
    const expandable = Boolean(step.detail);
    return (
      <div className="animate-in fade-in slide-in-from-top-1">
        <button
          type="button"
          aria-expanded={open}
          onClick={() => expandable && setUserOpen(!open)}
          className={`w-full flex items-center gap-1.5 py-0.5 -mx-1 px-1 rounded-md text-left transition-colors group ${
            expandable ? "hover:bg-muted/60 cursor-pointer" : "cursor-default"
          }`}
        >
          <Info className="w-3.5 h-3.5 text-sky-500/80 dark:text-sky-400/80 shrink-0" />
          <span className="text-xs text-muted-foreground group-hover:text-foreground transition-colors">
            {step.title}
          </span>
          {expandable && (
            <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform ${open ? "" : "-rotate-90"}`} />
          )}
        </button>
        {open && step.detail && (
          <pre className="whitespace-pre-wrap break-words text-xs leading-relaxed text-muted-foreground border-l-2 border-sky-200 dark:border-sky-900 pl-3 ml-[6px] mt-1 mb-2 max-h-64 overflow-auto">
            {step.detail}
          </pre>
        )}
      </div>
    );
  }

  return (
    <div className="animate-in fade-in slide-in-from-top-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setUserOpen(!open)}
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





/** 单条步骤（think/tool/context 分发）。 */
function StepRow({ step }: { step: ChatStep }) {
  if (step.type === "think") return <ReasoningRow step={step} />;
  if (step.type === "context") return <ContextRow step={step} />;
  return <ToolRow step={step} />;
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
 * 工作过程块（对齐 Codex/Claude Code 的执行流心智）：
 * - 过程与输出**常驻可见**：执行中完整时间线实时展示，完成后保持展开——
 *   此前完成后整块自动折叠为一行，执行中可见的输出在轮次结束瞬间"消失"
 *   （2026-10-02 用户反馈："过程里面的输出消失了，不会像 codex/claude code 那样"）；
 * - 用户手动收起后尊重用户选择（可随时再展开回看）；
 * - 折叠态仍保留「结果类」内容（photos_showcase 的画廊卡片）——
 *   过程可收起，结果必须可见（散图/拼图属过程，随过程一起收起）。
 */
export function AgentProcessBlock({
  steps,
  isTyping,
  durationMs,
}: {
  steps: ChatStep[];
  isTyping: boolean;
  durationMs?: number;
}) {
  // 默认展开:过程常驻可见;用户点击收起后保持收起
  const [collapsed, setCollapsed] = useState(false);
  if (steps.length === 0) return null;

  const toolCount = steps.filter((s) => s.type === "tool").length;
  const summary = toolCount > 0 ? `${toolCount} 次工具调用` : `${steps.length} 个步骤`;
  // 结果类内容:产物画廊(过程收起后仍展示;旧 nora-gallery 自动转换)。
  // 一条消息可能含多条画廊实例(每个工具结果一或多条),展平渲染。
  const galleries = steps
    .flatMap((s) => parseArtifactsFence(s.result?.content) ?? parseLegacyGalleryFence(s.result?.content) ?? [])
    .filter((g): g is ArtifactGallery => g !== null);

  return (
    <div className="animate-in fade-in">
      <button
        type="button"
        aria-expanded={!collapsed}
        onClick={() => setCollapsed((v) => !v)}
        className="group flex items-center gap-1.5 py-0.5 -mx-1 px-1 rounded-md text-left hover:bg-muted/60 cursor-pointer transition-colors"
      >
        <ChevronRight
          className={`w-3.5 h-3.5 text-muted-foreground/50 transition-transform ${collapsed ? "" : "rotate-90"}`}
        />
        <span className="text-xs font-medium text-muted-foreground group-hover:text-foreground transition-colors">
          {collapsed ? "查看工作过程" : "工作过程"}
        </span>
        <span className="text-[10px] text-muted-foreground/70 tabular-nums">
          · {summary}
          {durationMs != null && ` · ${(durationMs / 1000).toFixed(1)}s`}
        </span>
        {isTyping && <Loader2 className="w-3 h-3 text-blue-500 animate-spin shrink-0" />}
      </button>
      {collapsed ? (
        galleries.length > 0 && (
          <div className="mt-1.5 space-y-1.5">
            {galleries.map((g, i) => (
              <ArtifactsBlock key={i} gallery={g} />
            ))}
          </div>
        )
      ) : (
        <div className="mt-1">
          <AgentThoughtBlock steps={steps} />
        </div>
      )}
    </div>
  );
}

/**
 * 回答下方的执行元信息行(灰色小字):N 次工具调用 · N tokens · Ns。
 * 仅在轮次结束后展示。
 *
 * hideToolAndDuration: 折叠后的「查看工作过程」行已带"N 次工具调用 · Xs",
 * 此处不再重复,只补 tokens / 首字延迟。
 */
export function TurnMeta({
  steps,
  durationMs,
  usage,
  ttftMs,
  hideToolAndDuration = false,
}: {
  steps?: ChatStep[];
  durationMs?: number;
  usage?: { inputTokens?: number; outputTokens?: number; totalTokens?: number } | null;
  ttftMs?: number | null;
  hideToolAndDuration?: boolean;
}) {
  const toolCount = steps?.filter((s) => s.type === "tool").length ?? 0;
  const tokens = usage?.totalTokens ?? usage?.outputTokens ?? null;

  if (toolCount === 0 && tokens == null && durationMs == null) return null;

  const parts: string[] = [];
  if (!hideToolAndDuration && toolCount > 0) parts.push(`${toolCount} 次工具调用`);
  if (tokens != null) parts.push(`${tokens} tokens`);
  if (ttftMs != null && ttftMs >= 0) parts.push(`首字 ${(ttftMs / 1000).toFixed(1)}s`);
  if (!hideToolAndDuration && durationMs != null) parts.push(`${(durationMs / 1000).toFixed(1)}s`);
  if (parts.length === 0) return null;

  return (
    <div className="text-[10px] text-muted-foreground/70 tabular-nums pt-0.5">{parts.join(" · ")}</div>
  );
}
