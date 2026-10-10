import { lazy, Suspense, useState } from "react";
import { CheckCircle2, ChevronDown, CirclePause, Loader2, Table2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { useBrowser } from "@/hooks/useBrowser";
import { browserApi } from "@/lib/services/browserApi";
import { humanizeError, type FriendlyError } from "@/lib/errorMessages";

const BrowserResultsDialog = lazy(() => import("./BrowserResultsDialog"));

interface Props {
  sessionId: string;
  browser: ReturnType<typeof useBrowser>;
  isSending: boolean;
  onMessage: (content: string) => Promise<void>;
  onStop: () => void;
}

export function BrowserTaskCard({ sessionId, browser, isSending, onMessage, onStop }: Props) {
  const task = browser.state?.task;
  const [resultsOpen, setResultsOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<FriendlyError | null>(null);
  if (!task) return null;
  const finished = task.phase === "completed", cancelled = task.phase === "cancelled";
  const reviewing = task.phase === "review";
  const human = browser.state?.owner === "human";
  const status = cancelled ? "已取消" : isSending ? human ? "等待你交回浏览器" : reviewing ? "正在整理样本" : task.rowCount ? "正在收集资料" : "正在查看网页"
    : finished ? "已完成" : reviewing ? "样本待查看" : task.phase === "needs_check" ? "结果需要调整" : human ? "等待你交回浏览器" : task.rowCount ? "资料已采集，等待处理" : "任务已准备";
  const resume = async () => {
    setBusy(true); setError(null);
    try {
      if (human) await browserApi.control(sessionId, { action: "release" });
      await browser.refresh();
      await onMessage("请继续本次网页资料任务，先查看任务卡保存的要求和已有资料，沿用原来的字段与数量。需要先看样本时只采样本并等待我查看；样本已确认时继续全量采集、核验并在任务卡交付表格。用中文简短报告结果，不必自动打开文件查看器。");
    } catch (failure) { setError(humanizeError(failure)); }
    finally { setBusy(false); }
  };
  const cancel = async () => {
    onStop(); setBusy(true); setError(null);
    try { await browserApi.decide(sessionId, task.id, "cancel"); await browser.refresh(); }
    catch (failure) { setError(humanizeError(failure)); }
    finally { setBusy(false); }
  };
  return <section aria-label="网页任务进度" className="shrink-0 border-b border-border/60 bg-card px-3 py-2.5 [@container(min-width:640px)]:px-6">
    <div className="max-w-[820px] mx-auto space-y-2">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-xs">
        {isSending && !cancelled ? <Loader2 className="w-3.5 h-3.5 shrink-0 text-blue-600 animate-spin" /> : finished ? <CheckCircle2 className="w-3.5 h-3.5 shrink-0 text-emerald-600" /> : <CirclePause className="w-3.5 h-3.5 shrink-0 text-amber-600" />}
        <span className="font-semibold">网页资料</span>
        <span className="text-muted-foreground tabular-nums">{task.rowCount} 条{reviewing ? "样本" : "资料"}</span>
        <span className={`text-[11px] ${finished && !isSending ? "text-emerald-600" : "text-muted-foreground"}`}>{status}</span>
        <div className="ml-auto flex items-center gap-2">
          {(reviewing || task.latestReceipt) && <Button size="sm" className="h-7 gap-1.5 rounded-lg px-2.5 shadow-none" title={reviewing ? "查看收集样本" : "查看与调整结果"} onClick={() => setResultsOpen(true)}><Table2 />{reviewing ? "查看样本" : "查看结果"}</Button>}
          {!finished && !cancelled && !reviewing && <Button size="sm" variant="outline" className="h-7 rounded-lg px-2.5 shadow-none" disabled={isSending || busy} onClick={() => void resume()}>{human ? "交回并继续处理" : "继续处理"}</Button>}
        </div>
      </div>
      <details key={task.id} className="group/task text-[11px] text-muted-foreground">
        <summary className="flex min-w-0 cursor-pointer list-none items-center gap-2 rounded focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
          <span className="min-w-0 flex-1 truncate" title={task.task}>{task.task}</span>
          <span className="shrink-0">任务要求</span><ChevronDown className="w-3 h-3 shrink-0 transition-transform group-open/task:rotate-180" />
        </summary>
        <div className="mt-2 max-h-28 overflow-y-auto space-y-1.5 custom-scroll">
          <p className="text-foreground break-words">{task.task}</p>
          <div className="flex flex-wrap gap-x-3 gap-y-1">
            <span>目标至少 {task.minRows} 条 · 有效资料 {task.usableRows} 条</span>
            <span className="break-words">字段：{task.columns.join("、")}</span>
            {!finished && !cancelled && <button type="button" className="ml-auto hover:text-red-600 disabled:opacity-40" disabled={busy} onClick={() => void cancel()}>取消任务</button>}
          </div>
        </div>
      </details>
      {(!!task.duplicateCount || !!task.missingCount) && <p className="text-[11px] text-amber-600">{[task.duplicateCount && `${task.duplicateCount} 条重复`, task.missingCount && `${task.missingCount} 条字段缺失`].filter(Boolean).join(" · ")}</p>}
    {error && <div role="alert" className="text-xs text-red-600"><p>{error.message}</p><details><summary>错误详情</summary><p className="break-all">{error.raw}</p></details></div>}
    {resultsOpen && <Suspense fallback={null}><BrowserResultsDialog key={task.id} sessionId={sessionId} task={task} browser={browser} isSending={isSending} onMessage={onMessage} onClose={() => setResultsOpen(false)} /></Suspense>}
    </div>
  </section>;
}
