import { CornerDownLeft, ListOrdered, Loader2, Undo2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { refKey } from "@/lib/chatRefs";
import type { QueuedChatMessage } from "@/types";
import { RefChip } from "./RefChip";

interface ChatMessageQueueProps {
  messages: QueuedChatMessage[];
  isSending: boolean;
  isCompacting?: boolean;
  processingId: string | null;
  onInsert: (id: string) => void;
  onRemove: (id: string) => void;
  onRestore: (id: string) => void;
}

export function ChatMessageQueue({ messages, isSending, isCompacting, processingId, onInsert, onRemove, onRestore }: ChatMessageQueueProps) {
  if (messages.length === 0) return null;

  return (
    <section aria-label="排队消息" className="mb-3 overflow-hidden rounded-xl border border-border bg-muted/30">
      <div className="flex items-center justify-between gap-3 px-3 py-2 text-[11px] text-muted-foreground">
        <span className="flex shrink-0 items-center gap-1.5 font-medium">
          <ListOrdered className="h-3.5 w-3.5" /> 排队 · {messages.length}
        </span>
        <span className="text-right">{isSending ? "当前任务结束后依次发送" : "等待发送"}</span>
      </div>
      <ol className="max-h-[min(220px,25dvh)] divide-y divide-border/70 overflow-y-auto custom-scroll">
        {messages.map((message, index) => {
          const processing = processingId === message.id;
          const compact = message.kind === "compact";
          const insertLabel = compact ? isSending ? "等待压缩" : "压缩" : message.insertTurnId ? "确认插入" : isSending ? "插入" : "发送";
          return (
            <li key={message.id} className="flex items-start gap-2 px-3 py-2.5" aria-label={`排队消息 ${index + 1}`}>
              <span className="pt-1 text-[11px] tabular-nums text-muted-foreground">{index + 1}</span>
              <div className="min-w-0 flex-1">
                <p className="line-clamp-2 whitespace-pre-wrap break-words text-sm leading-relaxed" title={message.content}>
                  {message.content || "引用资料"}
                </p>
                {message.refs.length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {message.refs.map(ref => <RefChip key={refKey(ref)} chatRef={ref} />)}
                  </div>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-0.5">
                <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-xs"
                  disabled={processingId !== null || isCompacting || (compact && (isSending || index > 0))} onClick={() => onInsert(message.id)}
                  aria-label={`${insertLabel}排队消息 ${index + 1}`}
                  title={compact ? "在当前轮结束后按排队顺序压缩上下文" : isSending ? "在下一次模型请求前插入" : "发送这条消息"}>
                  {processing ? <Loader2 className="h-3 w-3 animate-spin" /> : <CornerDownLeft className="h-3 w-3" />}
                  {insertLabel}
                </Button>
                <Button variant="ghost" size="icon" className="h-7 w-7 text-muted-foreground"
                  disabled={processing || !!message.insertTurnId} onClick={() => onRestore(message.id)}
                  aria-label={`撤回编辑排队消息 ${index + 1}`} title="撤回到输入框编辑">
                  <Undo2 className="h-3.5 w-3.5" />
                </Button>
                <Button variant="ghost" size="icon" className="h-7 w-7 text-muted-foreground"
                  disabled={processing || !!message.insertTurnId} onClick={() => onRemove(message.id)}
                  aria-label={`删除排队消息 ${index + 1}`} title="删除排队消息">
                  <X className="h-3.5 w-3.5" />
                </Button>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
