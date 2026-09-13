import { AlertTriangle, Brain, Check, ChevronDown, FileText, Loader2, Wrench, Ban } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ChatStep, ContextFile } from "@/lib/api/chatApi";

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
  if (step.toolName === "run_command" && step.input?.target) {
    // 命令原文(后端把 command 放进 target);折叠行只给一瞥,审批卡/展开可见全文
    const cmd = step.input.target.replace(/\s+/g, " ").trim();
    return cmd.length > 64 ? `${cmd.slice(0, 64)}…` : cmd;
  }
  if (step.toolName === "manage_datasource" || step.toolName === "manage_service" || step.toolName === "manage_mcp") {
    // 无 action 上下文时(历史持久化缺 action)退回 target
    return [step.input?.target].filter(Boolean).join(" ") || "";
  }
  if (!step.input) return "";
  const json = JSON.stringify(step.input);
  return json.length > 72 ? `${json.slice(0, 72)}…` : json;
}

/**
 * 工具结果里的 Markdown 图片（如相册工具返回的 ![照片](url)）。
 *
 * 为什么需要：MCP 工具（相册等）会在结果文本里附 Markdown 图片链接，
 * 让"模型看到了什么"对用户可见——模型看了猫的照片，用户也该看到。
 * 只从 mcp__ 工具的结果里提取（普通工具结果里的 ![]() 可能是文件内容，不渲染）。
 */
function extractResultImages(content: string | null | undefined): { alt: string; url: string }[] {
  if (!content) return [];
  const out: { alt: string; url: string }[] = [];
  const re = /!\[([^\]]*)\]\(([^)\s]+)\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(content)) !== null) {
    const url = m[2];
    // 只渲染 http(s) 图片地址（相对路径/数据串不进 <img>）
    if (/^https?:\/\//i.test(url)) {
      out.push({ alt: m[1] || "图片", url });
    }
  }
  return out;
}

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

/** 工具步骤：单行 chip,可展开参数与结果详情。 */
function ToolRow({ step }: { step: ChatStep }) {
  const [open, setOpen] = useState(step.status === "running");
  const [userTouched, setUserTouched] = useState(false);
  const effectiveOpen = userTouched ? open : step.status === "running";
  const expandable = Boolean(step.input || step.result || step.detail);
  const lines = outputLineCount(step);
  const preview = argsPreview(step);
  const running = step.status === "running";
  // MCP 工具结果里的图片（相册照片/拼图等）：直接展示给用户，
  // 不用展开详情——"模型看到了什么"用户同屏可见
  const resultImages = !running && step.toolName?.startsWith("mcp__")
    ? extractResultImages(step.result?.content)
    : [];

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
      {resultImages.length > 0 && (
        <div className="ml-6 mt-1.5 flex flex-wrap gap-2 max-w-2xl">
          {resultImages.slice(0, 6).map((img, i) => (
            <a
              key={`${img.url}-${i}`}
              href={img.url}
              target="_blank"
              rel="noreferrer"
              title={`${img.alt}（点击查看原图）`}
              className="group/img block rounded-lg border border-border overflow-hidden bg-muted/40"
            >
              <img
                src={img.url}
                alt={img.alt}
                loading="lazy"
                className="h-24 w-auto max-w-[240px] object-cover transition-opacity group-hover/img:opacity-90"
              />
            </a>
          ))}
          {resultImages.length > 6 && (
            <span className="self-end text-[10px] text-muted-foreground">+{resultImages.length - 6} 张</span>
          )}
        </div>
      )}
      {effectiveOpen && expandable && (
        <ToolDetail step={step} />
      )}
    </div>
  );
}

function ToolDetail({ step }: { step: ChatStep }) {
  const result = step.result;
  const isCommand = step.toolName === "run_command";

  // run_command:命令以纯文本展示(不套 JSON);执行中显示实时输出流
  if (isCommand) {
    const cmd = step.input?.target ?? "";
    return (
      <div className="ml-6 my-1 space-y-1.5 max-w-2xl">
        <Section title="命令" content={cmd} />
        {step.status === "running" && (
          step.detail ? (
            <LiveOutput content={step.detail} />
          ) : (
            <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <Loader2 className="w-3 h-3 animate-spin" /> 等待输出…
            </div>
          )
        )}
        {result?.error ? (
          <Section title="执行结果" content={result.content ?? result.error} tone="error" />
        ) : result?.content != null ? (
          <Section
            title="执行结果"
            content={result.content}
            meta={result.truncated && <span className="text-amber-600 dark:text-amber-400">· 已截断</span>}
          />
        ) : null}
      </div>
    );
  }

  const inputJson = step.input ? JSON.stringify(step.input, null, 2) : null;

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

/** 实时输出(命令执行期间):终端习惯——等宽、深色、自动滚到底部。 */
function LiveOutput({ content }: { content: string }) {
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [content]);
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wide mb-0.5 flex items-center gap-1 text-muted-foreground">
        <Loader2 className="w-3 h-3 animate-spin" /> 实时输出
      </div>
      <pre
        ref={ref}
        className="whitespace-pre-wrap break-words rounded-md px-2 py-1.5 text-[11px] font-mono max-h-48 overflow-auto bg-[#1e1e1e] text-gray-300 custom-scroll"
      >
        {content}
      </pre>
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

/** 单条步骤（think/tool/context 分发）。 */
function StepRow({ step }: { step: ChatStep }) {
  if (step.type === "think") return <ReasoningRow step={step} />;
  if (step.type === "context") return <ContextRow step={step} />;
  return <ToolRow step={step} />;
}

/**
 * 注入上下文步骤(对齐 dsh 的注入可见性):折叠一行「注入 N 个文件/条目」,
 * 展开按 form 渲染——instructions=文件清单+日记清单,catalog=条目列表;
 * 未知 form 降级为通用展示(不丢内容)。注入内容是模型真实读到的上下文,
 * 展示只读、不产生任何动作。
 */
function ContextRow({ step }: { step: ChatStep }) {
  const [open, setOpen] = useState(false);
  const ctx = step.context;
  const files = ctx?.files ?? [];
  const entries = ctx?.entries ?? [];
  const dailyNotes = ctx?.dailyNotes ?? [];
  const form = ctx?.form;

  // 折叠行摘要:优先展示条目/文件名清单(dsh 的 collapsedContent 模式)
  const preview = form === "catalog"
    ? entries.map((e) => e.name).join(" · ")
    : files.map((f) => f.path).join(" · ");
  const truncatedCount = files.filter((f) => f.truncated).length;
  const missingCount = files.filter((f) => f.missing).length;

  return (
    <div className="animate-in fade-in slide-in-from-top-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-2 py-1.5 px-2.5 rounded-lg text-left border transition-colors max-w-2xl cursor-pointer hover:bg-muted/70 border-border bg-card"
      >
        <span className="shrink-0">
          <Check className="w-3.5 h-3.5 text-green-600 dark:text-green-500" />
        </span>
        <span className="text-xs font-medium text-foreground shrink-0 flex items-center gap-1">
          <FileText className="w-3 h-3 text-muted-foreground/60" />
          {step.title}
        </span>
        {preview && (
          <span className="text-[11px] text-muted-foreground truncate flex-1">{preview}</span>
        )}
        <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform shrink-0 ${open ? "" : "-rotate-90"}`} />
      </button>
      {open && (
        <div className="ml-6 my-1 space-y-1.5 max-w-2xl">
          {form === "catalog" ? (
            <div>
              <div className="text-[10px] uppercase tracking-wide mb-0.5 text-muted-foreground">
                启用技能（{entries.length}）· 正文按需读取
              </div>
              <ul className="rounded-md bg-muted/60 divide-y divide-border/60">
                {entries.map((entry, i) => (
                  <li key={i} className="px-2 py-1.5 flex items-baseline gap-2">
                    <code className="text-[11px] font-mono text-foreground shrink-0">{entry.name}</code>
                    {entry.category && (
                      <span className="text-[10px] text-muted-foreground/70 shrink-0">[{entry.category}]</span>
                    )}
                    {entry.description && (
                      <span className="text-[11px] text-muted-foreground truncate">{entry.description}</span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <div>
              <div className="text-[10px] uppercase tracking-wide mb-0.5 text-muted-foreground">
                注入文件（每轮自动带入模型上下文,点击查看注入正文）
              </div>
              <ul className="rounded-md bg-muted/60 divide-y divide-border/60">
                {files.map((file, i) => (
                  <ContextFileRow key={i} file={file} />
                ))}
              </ul>
              {(dailyNotes.length > 0 || truncatedCount > 0 || missingCount > 0) && (
                <div className="mt-1 text-[10px] text-muted-foreground/70 space-y-0.5">
                  {dailyNotes.length > 0 && (
                    <div>日记清单（不注入正文，按需读取）：{dailyNotes.join(" · ")}</div>
                  )}
                  {(truncatedCount > 0 || missingCount > 0) && (
                    <div>
                      {truncatedCount > 0 && <>· {truncatedCount} 个文件因预算/长度截断</>}
                      {missingCount > 0 && <> · {missingCount} 个文件缺失</>}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 单个注入文件行:点击展开显示模型实际读到的注入正文(dsh 的 instructions 形态:
 * 文件清单 + 原文——展示的就是模型读到的,含 framing/截断标记,不做二次加工)。
 * 缺失文件无正文,不可展开。
 */
function ContextFileRow({ file }: { file: ContextFile }) {
  const [open, setOpen] = useState(false);
  const hasContent = !file.missing && typeof file.content === "string" && file.content.length > 0;

  return (
    <li>
      <button
        type="button"
        aria-expanded={hasContent ? open : undefined}
        onClick={() => hasContent && setOpen((v) => !v)}
        className={`w-full px-2 py-1.5 flex items-center gap-2 text-left ${hasContent ? "cursor-pointer hover:bg-muted/80" : "cursor-default"}`}
      >
        <code className="text-[11px] font-mono text-foreground shrink-0">{file.path}</code>
        {file.missing ? (
          <span className="text-[10px] text-amber-600 dark:text-amber-400">缺失（占位注入）</span>
        ) : (
          <span className="text-[10px] text-muted-foreground/70 tabular-nums">
            {formatBytes(file.bytes ?? 0)}
          </span>
        )}
        {file.truncated && (
          <span className="text-[10px] text-amber-600 dark:text-amber-400">已截断</span>
        )}
        {hasContent && (
          <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform shrink-0 ml-auto ${open ? "" : "-rotate-90"}`} />
        )}
      </button>
      {open && hasContent && (
        <pre className="whitespace-pre-wrap break-words text-[11px] font-mono text-muted-foreground px-2 pb-2 max-h-64 overflow-auto">
          {file.content}
        </pre>
      )}
    </li>
  );
}

/** 字符数人性化:1.0KB / 328B */
function formatBytes(bytes: number): string {
  return bytes >= 1024 ? `${(bytes / 1024).toFixed(1)}KB` : `${bytes}B`;
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
