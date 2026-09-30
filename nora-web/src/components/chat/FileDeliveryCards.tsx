import { useState } from "react";
import { Check, Download, ExternalLink, FileText, RotateCcw } from "lucide-react";
import type { ChatMessage } from "@/lib/api/chatApi";
import { useFileViewer } from "@/hooks/useFileViewer";
import { viewerApi } from "@/lib/services/viewerApi";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { humanSize } from "@/lib/services/filesApi";
import { toast } from "sonner";

export function FileDeliveryCards({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  const [registered, setRegistered] = useState<string[]>([]);
  const [retrying, setRetrying] = useState<string | null>(null);
  const steps = msg.steps?.filter(step => step.status === "completed" || step.status === "partial" || (step.status === "failed" && step.result?.fileErrors?.length)) ?? [];
  const files = Array.from(new Map(steps.flatMap(step => (step.result?.files ?? []).map(file => [file.target, { file, stepId: step.id }] as const))).values());
  const errors = steps.flatMap(step => step.result?.fileErrors ?? []);
  const retry = async (target: string, name: string, stepId: string) => {
    setRetrying(target);
    try {
      const { files } = await viewerApi.resolve([target]);
      if (!files.length) throw new Error("文件已不可用");
      const result = await savedArtifactsApi.register({ kind: "workspace_file", path: target.slice(10), name, sessionId, messageKey: stepId });
      if (!result) throw new Error("成果登记失败，请稍后重试");
      setRegistered(previous => [...previous, target]);
      toast.success("成果已登记");
    } catch (error) { toast.error(error instanceof Error ? error.message : "登记失败"); }
    finally { setRetrying(null); }
  };
  if (!files.length && !errors.length) return null;
  return <div className="space-y-2 max-w-2xl" aria-label="本次对话的文件">
    {files.map(({ file, stepId }) => {
      const saved = file.delivery?.status === "registered" || registered.includes(file.target);
      const open = () => void useFileViewer.getState().openTargets([file.target], file.target, { sessionId, collection: files.map(item => item.file.target) });
      return <div key={file.target} className="rounded-xl border border-border bg-card p-3 flex items-center gap-3">
        <div className="shrink-0 rounded-lg bg-blue-50 dark:bg-blue-950/40 p-2.5 text-blue-600 dark:text-blue-400"><FileText className="w-5 h-5" /></div>
        <button type="button" className="min-w-0 flex-1 text-left" onClick={open}>
          <span className="block text-sm font-medium truncate">{file.name}</span>
          <span className="block mt-0.5 text-xs text-muted-foreground truncate">{humanSize(file.size)} · {file.target}</span>
          {saved && <span className="mt-1 inline-flex items-center gap-1 text-[11px] text-green-700 dark:text-green-400"><Check className="w-3 h-3" />已保存成果</span>}
          {file.delivery?.status === "failed" && !saved && <span className="block mt-1 text-[11px] text-amber-700 dark:text-amber-300">文件已保存，成果登记失败</span>}
        </button>
        {file.delivery?.status === "failed" && !saved && <button type="button" disabled={retrying === file.target} className="p-2 hover:bg-muted rounded-md" aria-label={`重试登记 ${file.name}`} onClick={() => void retry(file.target, file.name, stepId)}><RotateCcw className={`w-4 h-4 ${retrying === file.target ? "animate-spin" : ""}`} /></button>}
        <button type="button" className="p-2 hover:bg-muted rounded-md" aria-label={`打开 ${file.name}`} onClick={open}><ExternalLink className="w-4 h-4 text-muted-foreground" /></button>
        {file.capabilities.download && <a href={viewerApi.rawUrl(file, true)} download={file.name} className="p-2 hover:bg-muted rounded-md" aria-label={`下载 ${file.name}`}><Download className="w-4 h-4 text-muted-foreground" /></a>}
      </div>;
    })}
    {errors.map((error, index) => <p key={index} className="text-xs text-amber-700 dark:text-amber-300 break-all">{error.target}：{error.message}</p>)}
  </div>;
}
