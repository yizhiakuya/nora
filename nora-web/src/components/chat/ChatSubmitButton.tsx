import { ArrowUp, Loader2, Square } from "lucide-react";
import { Button } from "@/components/ui/button";

export function ChatSubmitButton({ running, stopping, uploading, hasDraft, onSend, onStop }: {
  running: boolean; stopping: boolean; uploading: boolean; hasDraft: boolean;
  onSend: () => void; onStop?: () => void;
}) {
  const active = running || stopping;
  const label = stopping ? "正在停止" : active ? "停止生成" : uploading ? "正在上传附件" : "发送";
  return <Button type="button" size="icon" variant="composer" className="ml-1 h-9 w-9 shrink-0"
    aria-label={label} title={label} aria-busy={stopping || (!active && uploading)}
    disabled={active ? stopping || !onStop : uploading || !hasDraft}
    onClick={active ? onStop : onSend}>
    {stopping || (!active && uploading) ? <Loader2 className="h-4 w-4 animate-spin" />
      : active ? <Square className="h-3.5 w-3.5 fill-current" /> : <ArrowUp className="h-4 w-4" />}
  </Button>;
}
