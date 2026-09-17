import { Check, ChevronDown, FileText } from "lucide-react";
import { useState } from "react";
import type { ChatStep, ContextFile } from "@/lib/api/chatApi";

/**
 * 注入上下文步骤行(2026-09-17 从 AgentThoughtBlock 拆出,对齐 dsh 的注入
 * 可见性):折叠一行「注入 N 个文件/条目」,展开按 form 渲染——
 * instructions=文件清单+日记清单,catalog=条目列表;未知 form 降级通用展示。
 */

/**
 * 注入上下文步骤(对齐 dsh 的注入可见性):折叠一行「注入 N 个文件/条目」,
 * 展开按 form 渲染——instructions=文件清单+日记清单,catalog=条目列表;
 * 未知 form 降级为通用展示(不丢内容)。注入内容是模型真实读到的上下文,
 * 展示只读、不产生任何动作。
 */
export function ContextRow({ step }: { step: ChatStep }) {
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
