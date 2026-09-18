import type { ReactNode } from "react";
import { FileCheck2, Sparkles } from "lucide-react";
import { prefillChatInput } from "@/lib/artifacts";

/**
 * 画廊外壳(所有画廊共享):卡片容器 + 标题区 + 摘要 + 统计条 + 底部注释 + 建议操作。
 *
 * 每个画廊组件(MediaGallery/FilesGallery/...)只需实现内容区,
 * 头部/底部样式由本组件统一——保证各画廊风格一致但不互相耦合。
 */

export interface GalleryAction {
  /** 按钮文字(简短,如「删掉空目录」) */
  label: string;
  /** 点击后预填进输入框的指令(用户可改后发送) */
  prompt: string;
}

export interface GalleryShellProps {
  title?: string;
  summary?: string;
  stats?: Array<{ label: string; value: string }>;
  note?: string;
  /** 建议操作(1-3 个;点击预填输入框,不自动发送) */
  actions?: GalleryAction[];
  children: ReactNode;
}

export function GalleryShell({ title, summary, stats, note, actions, children }: GalleryShellProps) {
  return (
    <div className="rounded-xl border border-border bg-card overflow-hidden max-w-2xl animate-in fade-in slide-in-from-bottom-1">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/70">
        <FileCheck2 className="w-3.5 h-3.5 text-emerald-500 dark:text-emerald-400 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{title || "操作产物"}</span>
        <span className="ml-auto text-[10px] text-muted-foreground/60 shrink-0 hidden sm:inline">
          操作产物
        </span>
      </div>
      {summary && (
        <div className="px-3 pt-2 text-[11px] text-muted-foreground leading-relaxed">{summary}</div>
      )}
      {stats && stats.length > 0 && (
        <div className="flex flex-wrap gap-2 px-3 pt-2.5">
          {stats.map((s, i) => (
            <div key={i} className="rounded-lg border border-border/70 bg-muted/30 px-2.5 py-1.5 min-w-[4.5rem]">
              <div className="text-sm font-bold text-foreground tabular-nums leading-tight">{s.value}</div>
              <div className="text-[10px] text-muted-foreground">{s.label}</div>
            </div>
          ))}
        </div>
      )}
      <div className="px-3 py-2.5">{children}</div>
      {actions && actions.length > 0 && (
        <div className="px-3 pb-2.5 flex flex-wrap gap-1.5">
          {actions.slice(0, 3).map((a, i) => (
            <button
              key={i}
              type="button"
              onClick={() => prefillChatInput(a.prompt)}
              title={`填入输入框:${a.prompt}`}
              className="inline-flex items-center gap-1 rounded-md border border-blue-200 dark:border-blue-900/60 bg-blue-50/60 dark:bg-blue-950/30 px-2 py-1 text-[11px] font-medium text-blue-700 dark:text-blue-300 hover:bg-blue-100/70 dark:hover:bg-blue-900/40 transition-colors cursor-pointer"
            >
              <Sparkles className="w-3 h-3" />
              {a.label}
            </button>
          ))}
        </div>
      )}
      {note && (
        <div className="px-3 py-2 border-t border-border/70 text-[11px] text-muted-foreground leading-relaxed">
          {note}
        </div>
      )}
    </div>
  );
}

/** 分组小标题(可选;多段内容时用)。 */
export function SectionLabel({ icon: Icon, label, count }: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  count?: number;
}) {
  return (
    <div className="flex items-center gap-1.5 mb-1.5">
      <Icon className="w-3 h-3 text-muted-foreground/70 shrink-0" />
      <span className="text-[11px] font-medium text-muted-foreground">{label}</span>
      {count != null && count > 0 && (
        <span className="text-[10px] text-muted-foreground/50 tabular-nums">{count}</span>
      )}
    </div>
  );
}
