import { AlertTriangle, Ban, Check, ChevronDown, Loader2, Wrench } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ChatStep } from "@/lib/api/chatApi";
import { GalleryBlock, parseGalleryFence } from "./GalleryBlock";
import { ImageLightbox } from "@/components/shared/ImageLightbox";

/**
 * 工具步骤行(2026-09-17 从 AgentThoughtBlock 拆出):单行 chip(名称 +
 * 参数摘要 + 状态),可展开参数/结果详情;MCP 结果的图片/画廊直出。
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

/**
 * 手机相册的 /content 原图有 /thumb 缩略图变体：网格用缩略图（快），
 * 灯箱用原图（清晰）。非相册地址原样返回。
 */
function thumbVariant(url: string): string {
  return url.replace(/\/content(\?|$)/, "/thumb$1");
}

/** 工具步骤：单行 chip,可展开参数与结果详情。 */
export function ToolRow({ step }: { step: ChatStep }) {
  const [open, setOpen] = useState(step.status === "running");
  const [userTouched, setUserTouched] = useState(false);
  // 结果散图点击 → 页内灯箱（不再跳外部标签页）
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
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
  // photos_showcase 的画廊块：结构化展示（标题/说明由 agent 填写），
  // 识别到围栏时不再渲染散图（避免同一批照片重复出现）
  const gallery = !running ? parseGalleryFence(step.result?.content) : null;

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
      {gallery && (
        <div className="ml-6 mt-1.5">
          <GalleryBlock data={gallery} />
        </div>
      )}
      {!gallery && resultImages.length > 0 && (
        <div className="ml-6 mt-1.5 flex flex-wrap gap-2 max-w-2xl">
          {resultImages.slice(0, 6).map((img, i) => (
            <button
              type="button"
              key={`${img.url}-${i}`}
              title={`${img.alt}（点击放大查看）`}
              onClick={() => setLightboxIndex(i)}
              className="group/img block rounded-lg border border-border overflow-hidden bg-muted/40 cursor-zoom-in"
            >
              <img
                src={thumbVariant(img.url)}
                alt={img.alt}
                loading="lazy"
                className="h-24 w-auto max-w-[240px] object-cover transition-opacity group-hover/img:opacity-90"
              />
            </button>
          ))}
          {resultImages.length > 6 && (
            <span className="self-end text-[10px] text-muted-foreground">+{resultImages.length - 6} 张</span>
          )}
        </div>
      )}
      {lightboxIndex !== null && (
        <ImageLightbox
          images={resultImages.map((img) => ({
            src: img.url,
            thumb: thumbVariant(img.url),
            alt: img.alt,
          }))}
          index={lightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onIndexChange={setLightboxIndex}
        />
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
