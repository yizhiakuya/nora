import { CircleHelp, Send } from "lucide-react";
import { useState } from "react";
import type { QuestionRequest } from "@/lib/api/chatApi";
import { answerQuestion } from "@/lib/api/agentApi";
import { useChatSessions } from "@/hooks/useChatSessions";

/**
 * ask_user 澄清提问卡(2026-09-29):agent 暂停轮次等待用户作答时显示。
 * 有 options 时点选即提交;始终保留自由输入(可选可填)。提交后整卡
 * 替换为「已回答」态——答案会作为工具结果回填给模型,同一轮继续执行,
 * 之后的工具步骤/回答照常流入思考块。
 */
export function QuestionCard({ question, onAnswered }: {
  question: QuestionRequest;
  onAnswered: (answer: string) => void;
}) {
  const sessionId = useChatSessions((s) => s.activeId);
  const [working, setWorking] = useState(false);
  const [answer, setAnswer] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);

  const submit = async (value: string) => {
    const trimmed = value.trim();
    if (!sessionId || working || submitted || !trimmed) return;
    setWorking(true);
    try {
      await answerQuestion(sessionId, question.questionToken, trimmed);
      setSubmitted(trimmed);
      onAnswered(trimmed);
    } catch {
      setWorking(false);
    }
  };

  if (submitted) {
    return (
      <div className="relative rounded-xl border border-blue-200 dark:border-blue-900/70 bg-blue-50/60 dark:bg-blue-950/20 p-3 animate-in fade-in">
        <div className="flex items-start gap-2.5">
          <CircleHelp className="w-4 h-4 mt-0.5 shrink-0 text-blue-500" />
          <div className="min-w-0 flex-1">
            <div className="text-xs font-semibold text-foreground">已回答</div>
            <div className="text-xs text-muted-foreground mt-1 break-words">{submitted}</div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="relative rounded-xl border border-blue-200 dark:border-blue-900/70 bg-blue-50/60 dark:bg-blue-950/20 p-3 animate-in fade-in slide-in-from-top-1">
      <div className="flex items-start gap-2.5">
        <CircleHelp className="w-4 h-4 mt-0.5 shrink-0 text-blue-500" />
        <div className="min-w-0 flex-1">
          <div className="text-xs font-semibold text-foreground">Agent 想先确认一个问题</div>
          <div className="text-xs text-foreground mt-1 break-words">{question.question}</div>
          {question.options && question.options.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mt-2">
              {question.options.map((opt) => (
                <button
                  key={opt}
                  type="button"
                  disabled={working}
                  onClick={() => void submit(opt)}
                  className="inline-flex items-center rounded-md border border-blue-300 dark:border-blue-800 bg-card px-2.5 py-1 text-[11px] font-medium text-foreground hover:bg-blue-100/60 dark:hover:bg-blue-900/40 disabled:opacity-50 transition-colors cursor-pointer"
                >
                  {opt}
                </button>
              ))}
            </div>
          )}
          <div className="flex items-center gap-2 mt-2">
            <input
              type="text"
              value={answer}
              disabled={working}
              onChange={(e) => setAnswer(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void submit(answer);
                }
              }}
              placeholder={question.options && question.options.length > 0 ? "或输入其他回答…" : "输入回答…"}
              className="flex-1 min-w-0 rounded-md border border-border bg-background px-2 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-blue-400 disabled:opacity-50"
            />
            <button
              type="button"
              disabled={working || !answer.trim()}
              onClick={() => void submit(answer)}
              className="inline-flex items-center gap-1 rounded-md bg-blue-600 px-2.5 py-1.5 text-[11px] font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors cursor-pointer"
            >
              <Send className="w-3 h-3" />回答
            </button>
          </div>
          {question.timeoutSeconds != null && (
            <div className="text-[10px] text-muted-foreground mt-1.5">
              {question.timeoutSeconds >= 60
                ? `${Math.round(question.timeoutSeconds / 60)} 分钟内未回答,Agent 将基于假设继续`
                : `${question.timeoutSeconds} 秒内未回答,Agent 将基于假设继续`}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
