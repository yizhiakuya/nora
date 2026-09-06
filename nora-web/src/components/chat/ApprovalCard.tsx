import { Check, ShieldAlert, X } from "lucide-react";
import { useState } from "react";
import type { ApprovalRequest } from "@/lib/api/chatApi";
import { resolveApproval } from "@/lib/api/agentApi";
import { useChatSessions } from "@/hooks/useChatSessions";

/**
 * 高风险操作审批卡:只有用户点击批准/拒绝才会解析服务端 pending future;
 * 模型文本不会触发这里的任何动作。
 */
export function ApprovalCard({ approval, onResolved }: {
  approval: ApprovalRequest;
  onResolved: () => void;
}) {
  const sessionId = useChatSessions((s) => s.activeId);
  const [working, setWorking] = useState(false);
  const [decision, setDecision] = useState<"approved" | "declined" | null>(null);

  const decide = async (approved: boolean) => {
    if (!sessionId || working || decision) return;
    setWorking(true);
    try {
      await resolveApproval(sessionId, approval.approvalToken, approved);
      setDecision(approved ? "approved" : "declined");
      onResolved();
    } catch {
      setWorking(false);
    }
  };

  return (
    <div className="relative rounded-xl border border-orange-200 dark:border-orange-900/70 bg-orange-50/70 dark:bg-orange-950/20 p-3 animate-in fade-in slide-in-from-top-1">
      <div className="flex items-start gap-2.5">
        <ShieldAlert className="w-4 h-4 mt-0.5 shrink-0 text-orange-500" />
        <div className="min-w-0 flex-1">
          <div className="text-xs font-semibold text-foreground">需要你的批准</div>
          <div className="text-xs text-foreground mt-1">{approval.summary}</div>
          <div className="text-[11px] text-muted-foreground mt-1 break-words">目标：{approval.target}</div>
          <div className="text-[11px] text-orange-700 dark:text-orange-300 mt-1.5 break-words">{approval.risk}</div>
          {!decision ? (
            <div className="flex items-center gap-2 mt-2.5">
              <button type="button" disabled={working} onClick={() => void decide(true)} className="inline-flex items-center gap-1 rounded-md bg-orange-500 px-2.5 py-1.5 text-[11px] font-medium text-white hover:bg-orange-600 disabled:opacity-50">
                <Check className="w-3 h-3" />批准
              </button>
              <button type="button" disabled={working} onClick={() => void decide(false)} className="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-[11px] font-medium text-foreground hover:bg-muted disabled:opacity-50">
                <X className="w-3 h-3" />拒绝
              </button>
              <span className="text-[10px] text-muted-foreground">120 秒后自动拒绝</span>
            </div>
          ) : (
            <div className={`text-[11px] mt-2 font-medium ${decision === "approved" ? "text-green-600 dark:text-green-400" : "text-muted-foreground"}`}>
              {decision === "approved" ? "已批准，Agent 继续执行" : "已拒绝，Agent 跳过该操作"}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
