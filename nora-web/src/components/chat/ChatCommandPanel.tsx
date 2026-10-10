import { useEffect, useState } from "react";
import { Loader2, RefreshCw, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { fetchSessionContext, type SessionContextView } from "@/lib/services/chatCommandsApi";
import { formatTokenCount } from "@/lib/tokenEstimate";
import { humanizeError } from "@/lib/errorMessages";
import { PERMISSION_MODE_META, type PermissionMode } from "@/lib/api/chatApi";
import type { ContextMeter } from "@/lib/chatContext";
import type { ChatCommandPanel as Panel } from "@/hooks/useChatCommands";
import { useModelProviders, resolveDefaultProvider } from "@/hooks/useModelProviders";
import { useFileViewer } from "@/hooks/useFileViewer";

export function ChatCommandPanel({ panel, onClose, sessionId, meter, isSending, isCompacting, queueCount, permissionMode, reasoningLevel }: {
  panel: Exclude<Panel, "help">; onClose: () => void; sessionId: string; meter: ContextMeter;
  isSending: boolean; isCompacting: boolean; queueCount: number; permissionMode: PermissionMode; reasoningLevel?: string;
}) {
  const [view, setView] = useState<SessionContextView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const model = useModelProviders(state => state.defaultModel);
  const provider = useModelProviders(state => resolveDefaultProvider(state.providers, state.defaultModel, state.defaultProviderId)?.name);
  useEffect(() => {
    let current = true;
    setLoading(true); setError(null); setView(null);
    void fetchSessionContext(sessionId).then(data => { if (current) setView(data); })
      .catch(failure => { if (current) setError(humanizeError(failure).message); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [panel, sessionId, isSending, revision]);
  const checkpoint = view?.checkpoint;
  const usage = meter.usage ?? view?.latestUsage;
  const requestUsage = meter.usage?.source === "upstream" ? meter.usage : view?.latestRequestUsage;
  const token = (value: number | null | undefined) => value == null ? "未返回" : `${formatTokenCount(value)} tokens`;
  const rows = panel === "status" ? [
    ["会话", sessionId], ["当前运行", isCompacting ? "正在压缩上下文" : isSending ? "正在处理" : "空闲"],
    ["排队", `${queueCount} 项`], ["后续请求默认模型", `${model}${provider ? ` · ${provider}` : ""}`],
    ["后续请求默认思考等级", reasoningLevel ?? "跟随模型默认"], ["默认权限", PERMISSION_MODE_META[permissionMode].label],
    ["最近请求标识", usage?.modelKey ?? "暂无请求"],
  ] : [
    ["当前工作上下文", `${meter.estimated ? "≈" : ""}${token(meter.tokens)}`],
    [meter.windowConfigured ? "配置的模型窗口" : "默认整理预算", token(meter.limit)],
    ["自动整理", `下一次请求前 ≥${token(Math.floor(meter.limit * 0.83))}，目标 ${token(Math.floor(meter.limit * 0.65))}`],
    ["计量来源", usage?.source === "upstream" ? "上游计数 + 后续新增内容估算" : "估算；下一次请求返回用量后校准"],
    ["最近实测完整输入", token(requestUsage?.promptTokens)], ["其中缓存读取", token(requestUsage?.cacheReadInputTokens)],
    ["其中缓存写入", token(requestUsage?.cacheCreationInputTokens)], ["最近实测输出（含推理）", token(requestUsage?.outputTokens)],
  ];
  return <div className="flex min-h-0 flex-col">
    <div className="flex shrink-0 items-center justify-between border-b border-border/60 px-4 py-3">
      <h3 className="text-sm font-medium">{panel === "context" ? "上下文" : "会话状态"}</h3>
      <Button variant="ghost" size="icon" className="h-7 w-7 text-muted-foreground" onClick={onClose} aria-label="关闭命令详情"><X className="h-3.5 w-3.5" /></Button>
    </div>
    <div className="min-h-0 space-y-4 overflow-y-auto px-4 py-2 text-xs no-scrollbar">
      <dl className="divide-y divide-border">
        {rows.map(([label, value]) => <div key={label} className="grid grid-cols-[120px_minmax(0,1fr)] gap-3 py-2.5">
          <dt className="text-muted-foreground">{label}</dt><dd className="break-words leading-relaxed tabular-nums">{value}</dd>
        </div>)}
      </dl>
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">压缩检查点</span>
        <Button variant="ghost" size="sm" className="h-7 gap-1 text-xs" disabled={loading} onClick={() => setRevision(value => value + 1)}>
          {loading ? <Loader2 className="h-3 w-3 animate-spin" /> : <RefreshCw className="h-3 w-3" />}刷新
        </Button>
      </div>
      {error && <p role="alert" className="whitespace-pre-wrap break-words text-destructive">{error}</p>}
      {checkpoint ? <div className="space-y-3 rounded-xl border border-border bg-muted/30 p-3">
        <p className="text-muted-foreground">{checkpoint.createdAt.replace("T", " ")} · ≈{formatTokenCount(checkpoint.beforeTokens)} → ≈{formatTokenCount(checkpoint.contextUsage.promptTokens)} tokens</p>
        <details><summary className="cursor-pointer">查看保存的摘要</summary><p className="mt-2 whitespace-pre-wrap break-words leading-relaxed">{checkpoint.summary}</p></details>
        <p className="text-muted-foreground">生成摘要的独立用量：输入 {token(checkpoint.summaryInputTokens)} · 输出 {token(checkpoint.summaryOutputTokens)}</p>
        <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => { onClose(); void useFileViewer.getState().openTargets([`workspace:${checkpoint.archivePath}`], undefined, { sessionId }); }}>查看原始记录</Button>
      </div> : !loading && !error && <p className="text-muted-foreground">尚未保存压缩检查点。输入 /compact 可压缩较早的上下文。</p>}
    </div>
  </div>;
}
