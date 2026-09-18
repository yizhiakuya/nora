import { AlertTriangle, Ban, Check, ChevronDown, Loader2, Wrench } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ChatStep, ChatStepProgress } from "@/lib/api/chatApi";
import { GalleryBlock, parseGalleryFence } from "./GalleryBlock";
import { ImageLightbox } from "@/components/shared/ImageLightbox";
import { mediaCacheUrl, thumbVariant } from "@/lib/mediaCache";

/**
 * 工具步骤行(2026-09-17 从 AgentThoughtBlock 拆出):单行 chip(名称 +
 * 参数摘要 + 状态),可展开参数/结果详情;MCP 结果的图片/画廊直出。
 *
 * 批量任务(fetch_media 等)带结构化 progress 时渲染实时进度卡片:
 * 进度条 + 完成数/剩余数 + 字节/速率/ETA + 当前文件(并行路数)。
 */

function outputLineCount(step: ChatStep): number | null {
  if (step.result?.lineCount != null) return step.result.lineCount;
  if (step.result?.content == null) return null;
  return step.result.content.split("\n", -1).length;
}

/** 字节人类可读(进度卡片用;与 filesApi.humanSize 同口径,补 GB 档)。 */
function fmtBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/** 秒 → "1 分 30 秒" / "2 小时 5 分"(后端同逻辑,前端兜底渲染)。 */
function fmtEta(seconds: number): string {
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return s > 0 ? `${m} 分 ${s} 秒` : `${m} 分`;
  }
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return m > 0 ? `${h} 小时 ${m} 分` : `${h} 小时`;
}

/**
 * 批量任务实时进度卡片(fetch_media):进度条 + 计数 + 字节/速率/ETA +
 * 当前文件。running 期间始终可见(不折叠)——这就是「下载进行到哪了」的
 * 主反馈;终态步骤不带 progress(后端设计),自动让位给结果摘要。
 */
function ProgressPanel({ progress }: { progress: ChatStepProgress }) {
  const listing = progress.phase === "listing";
  const done = progress.done ?? 0;
  const total = progress.total ?? 0;
  const remaining = Math.max(0, total - done);
  const pct = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : null;
  const rate = progress.bytesPerSec;
  const eta = progress.etaSeconds;

  if (listing) {
    return (
      <div className="ml-6 mt-1 max-w-2xl rounded-lg border border-border bg-card px-3 py-2">
        <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
          <Loader2 className="w-3 h-3 animate-spin text-blue-500 shrink-0" />
          <span>正在获取清单…</span>
          {done > 0 && <span className="tabular-nums">已取到 {done} 条</span>}
        </div>
      </div>
    );
  }

  return (
    <div className="ml-6 mt-1 max-w-2xl rounded-lg border border-border bg-card px-3 py-2 space-y-1.5">
      {/* 进度条 + 完成数/剩余数 */}
      <div className="flex items-center gap-2">
        <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
          {pct != null ? (
            <div
              className="h-full rounded-full bg-blue-500 transition-[width] duration-300 ease-out"
              style={{ width: `${pct}%` }}
            />
          ) : (
            <div className="h-full w-1/3 rounded-full bg-blue-500/70 animate-pulse" />
          )}
        </div>
        <span className="text-[11px] tabular-nums text-foreground shrink-0">
          {done}/{total || "?"}
        </span>
        {total > 0 && (
          <span className="text-[10px] tabular-nums text-muted-foreground shrink-0">
            {pct != null && `${pct}% · `}还剩 {remaining} 个
          </span>
        )}
      </div>
      {/* 字节 / 速率 / ETA */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] tabular-nums text-muted-foreground">
        <span>
          已传 {fmtBytes(progress.bytesDone)}
          {progress.bytesTotal != null && progress.bytesTotal > 0 && ` / ${fmtBytes(progress.bytesTotal)}`}
        </span>
        {rate != null && rate > 0 && (
          <span className="text-foreground">{fmtBytes(rate)}/s</span>
        )}
        {eta != null && <span>约剩 {fmtEta(eta)}</span>}
      </div>
      {/* 当前文件 + 序号 + 并行路数 */}
      {progress.currentFile && (
        <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground min-w-0">
          <Loader2 className="w-3 h-3 animate-spin text-blue-500 shrink-0" />
          <span className="truncate">
            正在下载 <span className="text-foreground">{progress.currentFile}</span>
          </span>
          {progress.currentIndex != null && progress.currentIndex > 0 && (
            <span className="shrink-0 tabular-nums">第 {progress.currentIndex} 个</span>
          )}
          {progress.active != null && progress.active > 1 && (
            <span className="shrink-0">· 并行 {progress.active} 路</span>
          )}
        </div>
      )}
    </div>
  );
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
  if (step.toolName === "manage_datasource" || step.toolName === "manage_service" || step.toolName === "manage_mcp"
      || step.toolName === "manage_knowledge" || step.toolName === "manage_automation" || step.toolName === "search_knowledge") {
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
 * 手机相册的 /content 原图有 /thumb 缩略图变体:网格用缩略图(快),
 * 灯箱用原图(清晰)。缩略图变体与缓存 URL 工具见 @/lib/mediaCache。
 */

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
        {/* running 中的批量任务:chip 行内联进度(不展开也能看到 n/N) */}
        {running && step.progress && step.progress.phase !== "listing" && step.progress.total != null && (
          <span className="text-[10px] text-blue-600 dark:text-blue-400 shrink-0 tabular-nums">
            {step.progress.done ?? 0}/{step.progress.total}
          </span>
        )}
        {running && step.progress?.phase === "listing" && (
          <span className="text-[10px] text-muted-foreground shrink-0">获取清单…</span>
        )}
        {step.status === "failed" && <span className="text-[10px] text-red-600 dark:text-red-400 shrink-0">失败</span>}
        {step.status === "declined" && <span className="text-[10px] text-amber-600 dark:text-amber-400 shrink-0">已拦截</span>}
        {step.duration && <span className="text-[10px] text-muted-foreground/60 tabular-nums shrink-0">{step.duration}</span>}
        {expandable && <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform shrink-0 ${effectiveOpen ? "" : "-rotate-90"}`} />}
      </button>
      {/* 批量任务进度卡片:running 期间始终可见(不折叠)——「下载到哪了」的主反馈 */}
      {running && step.progress && <ProgressPanel progress={step.progress} />}
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
                src={mediaCacheUrl(thumbVariant(img.url))}
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
            src: mediaCacheUrl(img.url),
            thumb: mediaCacheUrl(thumbVariant(img.url)),
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
