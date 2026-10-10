import { useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
import { useBrowserResults } from "@/hooks/useBrowserResults";
import type { useBrowser } from "@/hooks/useBrowser";
import { useFileViewer } from "@/hooks/useFileViewer";
import { viewerApi } from "@/lib/services/viewerApi";
import { browserApi } from "@/lib/services/browserApi";
import { humanizeError } from "@/lib/errorMessages";
import type { BrowserTask } from "@/types";
import { BrowserResultTable } from "./BrowserResultTable";

interface Props {
  sessionId: string;
  task: BrowserTask;
  browser: ReturnType<typeof useBrowser>;
  isSending: boolean;
  onMessage: (content: string) => Promise<void>;
  onClose: () => void;
}

export default function BrowserResultsDialog({ sessionId, task, browser, isSending, onMessage, onClose }: Props) {
  const result = useBrowserResults(sessionId, task);
  const [feedback, setFeedback] = useState("");
  const [deciding, setDeciding] = useState(false);
  const { page, selected, receipt, error } = result;
  const preview = !receipt;
  const disabled = result.loading || result.busy || deciding || isSending;
  const send = async (content: string) => {
    if (browser.state?.owner === "human") await browserApi.control(sessionId, { action: "release" });
    await browser.refresh();
    onClose();
    void onMessage(content).catch(failure => toast.error(humanizeError(failure).message));
  };
  const decide = async (action: "approve" | "resample") => {
    setDeciding(true); result.setError(null);
    try {
      await browserApi.decide(sessionId, task.id, action, feedback);
      await send(action === "resample"
        ? `这批样本需要调整：${feedback}。请按本次网页任务的原有字段重新采最多三条样本，然后结束本轮等我查看。先读取当前任务设置。`
        : `我已在任务卡确认样本，请按本次网页任务的字段和数量继续收集并核验，沿用已保存的任务设置。${feedback ? `补充要求：${feedback}。` : ""}用中文简短报告结果，表格在任务卡查看，不必自动打开文件查看器。`);
    } catch (failure) { result.setError(humanizeError(failure)); }
    finally { setDeciding(false); }
  };
  const save = async () => {
    const next = await result.save();
    if (!next) return;
    await browser.refresh();
    if (next.verified) toast.success("已保存新版本，核验通过");
    else toast.warning("已保存新版本，仍有项目需要补采或调整");
  };
  return <Modal isOpen onClose={onClose} title={preview ? "查看收集样本" : "查看与调整收集结果"} width="w-[1080px] max-w-[calc(100vw-24px)]"
    closeOnOutsideClick={!disabled} closeOnEscape={!result.busy && !deciding} hideCloseButton={result.busy || deciding}
    footer={preview ? <><Button variant="outline" disabled={disabled || !feedback.trim()} onClick={() => void decide("resample")}>按要求重新采样</Button>
      <Button disabled={disabled || !page?.total} onClick={() => void decide("approve")}>{deciding && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}确认并继续收集</Button></>
      : <><Button variant="outline" disabled={disabled || !receipt} onClick={() => {
        if (receipt) { onClose(); void useFileViewer.getState().openTargets([receipt.reportTarget], receipt.reportTarget, { sessionId }); }
      }}>查看核验说明</Button>
        {receipt && <Button variant="outline" asChild><a href={viewerApi.rawUrl({ target: receipt.csvTarget, version: receipt.verifiedAt }, true)} download><Download className="w-4 h-4 mr-2" />下载当前版本 CSV</a></Button>}
        <Button disabled={disabled || !!result.newReceipt || !result.changed || !selected.size} onClick={() => void save()}>{result.busy && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}保存选中结果</Button></>}>
    <div className="space-y-4">
      <p className="text-sm font-medium">{task.task}</p>
      {result.newReceipt && <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-amber-50 dark:bg-amber-950/30 p-3 text-xs text-amber-800 dark:text-amber-300">
        <p>补采已生成新结果。当前选行草稿仍保留，载入新结果后才能继续保存。</p>
        <Button variant="outline" size="sm" disabled={result.busy || result.loading} onClick={result.loadLatest}>放弃选行并载入新结果</Button>
      </div>}
      <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
        <span>{preview ? `${page?.total ?? 0} 条样本 · 看看收集方向是否符合需求` : `保留 ${selected.size} 条 · 要求至少 ${task.minRows} 条`}</span>
        {!preview && page && page.total !== selected.size && <span>已采集 {page.total} 条候选，勾选的行会进入下载文件</span>}
        {!preview && page?.duplicateGroups.some(group => group.filter(id => selected.has(id)).length > 1) && <button type="button" className="text-blue-600 hover:underline disabled:opacity-40" disabled={disabled} onClick={result.dedupe}>去除完全重复项</button>}
        {!preview && result.changed && <span className="text-amber-600">选行尚未保存，下载仍为上次版本</span>}
      </div>
      {result.loading && <p role="status" className="flex gap-2 items-center text-xs text-muted-foreground"><Loader2 className="w-3 h-3 animate-spin" />正在读取真实采集资料…</p>}
      {page && <BrowserResultTable page={page} selected={selected} editable={!preview} disabled={disabled} toggle={result.toggle} setOffset={result.setOffset} />}
      {!preview && ((selected.size < task.minRows) || (receipt && !receipt.verified)) && <div className="p-3 rounded-lg bg-amber-50 dark:bg-amber-950/30 text-xs text-amber-800 dark:text-amber-300 space-y-1">
        <p>{selected.size < task.minRows ? `当前保留数量不足，还需要至少 ${task.minRows - selected.size} 条；原任务要求保持不变。` : "已保存的结果仍有项目需要检查。"}</p>
        {!result.changed && receipt?.checks.map(check => <p key={check}>{check}</p>)}
      </div>}
      {preview || (receipt && !receipt.verified) ? <label className="block text-sm space-y-2">{preview ? "调整要求（可选）" : "补采要求（可选）"}
        <textarea aria-label={preview ? "调整要求" : "补采要求"} value={feedback} onChange={event => setFeedback(event.target.value)} maxLength={1500} disabled={disabled} rows={2}
          placeholder="例如：不要分类标题，只收集可打开的文章链接" className="w-full rounded-md border bg-transparent p-3 text-sm outline-none focus:ring-1 focus:ring-blue-500" />
        {!preview && <Button type="button" variant="outline" disabled={disabled || result.changed} onClick={() => {
          setDeciding(true); void send(`请继续补采本次网页任务，达到原来的字段和数量要求。保留我在结果表格中选中的资料，已排除的行不要重新纳入。先读取当前任务和最新结果。${feedback ? `补充要求：${feedback}。` : ""}完成后在任务卡交付新表格，不必自动打开文件查看器。`)
            .catch(failure => { result.setError(humanizeError(failure)); setDeciding(false); });
        }}>让 Agent 继续补采</Button>}
      </label> : null}
      {error && <div role="alert" className="text-xs text-red-600"><p>{error.message}</p>{error.hint && <p>{error.hint}</p>}<details className="mt-1"><summary>错误详情</summary><p className="break-all">{error.raw}</p></details></div>}
    </div>
  </Modal>;
}
