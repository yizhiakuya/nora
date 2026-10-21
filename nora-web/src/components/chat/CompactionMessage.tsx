import { useState } from "react";
import { ChevronRight, Loader2, Minimize2 } from "lucide-react";
import type { ChatMessage } from "@/lib/api/chatApi";
import { Button } from "@/components/ui/button";
import { usePreferences } from "@/hooks/usePreferences";
import { ReasoningTokens } from "./ReasoningTokens";
import { CompactionContent } from "./ContextStepRow";

export function CompactionMessage({ msg, sessionId, onRetry, canRetry }: { msg: ChatMessage; sessionId?: string; onRetry?: (id: string) => void; canRetry?: boolean }) {
  const detailed = usePreferences(state => state.reasoningSummary === "detailed");
  const [open, setOpen] = useState(detailed);
  const step = msg.steps?.find(item => item.id === "s-compaction");
  const reasoning = msg.steps?.filter(item => item.id.startsWith("s-reasoning-")).map(item => item.detail ?? "").join("\n") ?? "";
  const interrupted = msg.stopped || msg.turnMetrics?.status === "interrupted";
  const failed = !!msg.error || msg.turnMetrics?.status === "failed";
  const title = msg.isTyping ? "正在压缩上下文" : interrupted ? "压缩已中断，原上下文保留" : failed ? "压缩失败，原上下文保留" : "上下文已压缩";
  return <section aria-label="上下文压缩" className="rounded-xl border border-border bg-muted/25 px-3 py-2.5 text-xs">
    <div className="flex flex-wrap items-center gap-2">
      {msg.isTyping ? <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" /> : <Minimize2 className="h-3.5 w-3.5 text-muted-foreground" />}
      <button type="button" aria-label={title} aria-expanded={open} onClick={() => setOpen(value => !value)} className="flex min-w-0 cursor-pointer items-center gap-1.5 py-1 text-left">
        <span role="status">{title}</span><ChevronRight className={`h-3 w-3 shrink-0 text-muted-foreground ${open ? "rotate-90" : ""}`} />
      </button>
      {reasoning && msg.isTyping && <ReasoningTokens text={reasoning} active />}
      {!msg.isTyping && !failed && !interrupted && <span className="text-muted-foreground">{step?.detail?.split("\n")[0]}</span>}
    </div>
    {msg.error && <p role="alert" className="mt-2 whitespace-pre-wrap break-words text-destructive">{msg.error}</p>}
    {open && <div className="mt-2 space-y-3 border-t border-border pt-3">
      {msg.content && msg.content !== "/compact" && <p className="whitespace-pre-wrap break-words text-muted-foreground">{msg.content}</p>}
      {step && <CompactionContent step={step} sessionId={sessionId} />}
      {reasoning && <details><summary className="cursor-pointer text-muted-foreground">生成摘要的思考过程</summary><p className="mt-2 whitespace-pre-wrap break-words leading-relaxed text-muted-foreground">{reasoning}</p></details>}
    </div>}
    {(failed || interrupted) && onRetry && <Button variant="outline" size="sm" className="mt-2 h-7 text-xs" disabled={!canRetry} onClick={() => onRetry(msg.id)}>重新压缩</Button>}
  </section>;
}
