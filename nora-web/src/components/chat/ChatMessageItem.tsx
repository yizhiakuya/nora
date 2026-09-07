import { Sparkles, Database, MessageSquare, BookOpen, FileCode, Server, FileText, Check, RotateCcw, ChevronDown, AlertTriangle } from "lucide-react";
import { useState } from "react";
import { AgentThoughtBlock, TurnMeta } from "./AgentThoughtBlock";
import { ApprovalCard } from "./ApprovalCard";
import { ChatMessage } from "@/lib/api/chatApi";
import { Markdown } from "@/components/shared/Markdown";
import { toast } from "sonner";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useChatSessions } from "@/hooks/useChatSessions";
import { USE_BACKEND } from "@/lib/api/client";

/** 错误图标与配色(按 kind 微调,不喧宾夺主) */
const ERROR_ICON: Record<string, React.ElementType> = {
  network: Server,
  auth: Sparkles,
  "rate-limit": AlertTriangle,
  timeout: AlertTriangle,
  provider: Sparkles,
  approval: BookOpen,
  unknown: AlertTriangle,
};

const SOURCE_ICON: Record<string, React.ElementType> = {
  file: FileText,
  database: Database,
  repo: FileCode,
  environment: Server,
  chat: MessageSquare,
};

function SourceCitations({ sources }: { sources: NonNullable<ChatMessage["sources"]> }) {
  return (
    <div className="relative animate-in fade-in slide-in-from-bottom-2">
      <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-purple-50 dark:bg-purple-950/40 border border-purple-100 dark:border-purple-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)] dark:shadow-[0_0_0_2px_rgba(17,24,39,1)]">
        <BookOpen className="w-[10px] h-[10px] text-purple-500 dark:text-purple-400" />
      </div>
      <div className="pt-0.5">
        <div className="text-[10px] text-muted-foreground mb-2">引用来源 · {sources.length} 个知识库片段 {sources.some((s) => s.score < 0.5) && <span className="text-amber-600 dark:text-amber-400">· 含低置信度内容</span>}</div>
        <div className="space-y-2">
          {sources.map((s, i) => {
            const Icon = SOURCE_ICON[s.source] ?? FileText;
            return (
              <div key={i} className="bg-card border border-border rounded-xl p-3 relative overflow-hidden">
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-purple-500" style={{ opacity: s.score }} />
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <Icon className="w-3 h-3 text-purple-500 dark:text-purple-400 shrink-0" />
                    <span className="text-xs font-medium text-foreground truncate">{s.docName}</span>
                    <span className="text-[9px] text-muted-foreground shrink-0">chunk #{s.chunkIndex}</span>
                  </div>
                  <span className={`text-[9px] font-bold ${s.score < 0.5 ? "text-amber-600 bg-amber-50 dark:text-amber-400 dark:bg-amber-950/40" : "text-purple-600 bg-purple-50 dark:text-purple-400 dark:bg-purple-950/40"} px-1.5 py-0.5 rounded-full tabular-nums shrink-0`}>
                    {(s.score * 100).toFixed(0)}%
                  </span>
                </div>
                <p className="text-[11px] text-muted-foreground leading-relaxed">{s.snippet}</p>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function SaveToKnowledgeButton({ msg }: { msg: ChatMessage }) {
  const [saved, setSaved] = useState(false);
  const addChatDoc = useKnowledgeDocs((s) => s.addChatDoc);

  const handleSave = () => {
    if (saved) return;
    const title = `对话结论 · ${msg.content.slice(0, 24).replace(/[#*\n]/g, "").trim()}…`;
    addChatDoc(title, msg.content);
    setSaved(true);
    toast[USE_BACKEND ? "info" : "success"](USE_BACKEND ? "当前后端暂未提供对话文本入库接口，已保存到本地" : "已保存到知识库（对话产出）");
  };

  return (
    <button
      type="button"
      onClick={handleSave}
      className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${saved ? "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800" : "bg-card text-muted-foreground border-border hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700"}`}
    >
      {saved ? <Check className="w-3 h-3" /> : <BookOpen className="w-3 h-3" />}
      {saved ? "已保存到知识库" : "保存到知识库"}
    </button>
  );
}

export function ChatMessageItem({ msg, onRetry, canRetry = true }: { msg: ChatMessage; onRetry?: (msgId: string) => void; canRetry?: boolean }) {
  const sessionId = useChatSessions((s) => s.activeId);
  const [copied, setCopied] = useState(false);
  const [rawExpanded, setRawExpanded] = useState(false);

  const copyId = async () => {
    try {
      await navigator.clipboard.writeText(sessionId ?? "");
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* 剪贴板不可用时忽略 */
    }
  };

  const copyContent = async () => {
    try {
      await navigator.clipboard.writeText(msg.content);
      toast.success("已复制回答内容");
    } catch {
      toast.error("复制失败");
    }
  };

  if (msg.role === 'user') {
    return (
      <>
        <div className="flex gap-4 animate-in fade-in slide-in-from-bottom-2">
            <div className="flex-1"></div>
            <div className="bg-background p-4 rounded-2xl rounded-tr-sm border border-border text-sm leading-relaxed max-w-[80%] whitespace-pre-wrap">
                {msg.content}
            </div>
            <div className="w-8 h-8 rounded-full flex-shrink-0 bg-blue-500 text-white text-[10px] font-bold flex items-center justify-center shadow-sm">NC</div>
        </div>
        <div className="text-right text-[10px] text-muted-foreground pr-12 mt-1">{msg.timestamp}</div>
      </>
    );
  }

  return (
    <>
      <div className="flex gap-4 animate-in fade-in slide-in-from-bottom-2">
          <div className="w-8 h-8 bg-blue-600 dark:bg-blue-500 rounded-full flex items-center justify-center text-white flex-shrink-0 shadow-sm mt-1">
              <Sparkles className="w-4 h-4" />
          </div>
          <div className="flex-1 overflow-hidden">
              <div className="text-sm font-medium flex items-center gap-2 mb-4">AI 助理 <span className="text-[10px] text-muted-foreground font-normal">{msg.timestamp}</span></div>
              
              <div className="relative pl-6 space-y-3 before:absolute before:inset-y-2 before:left-2.5 before:w-px before:bg-gray-200 dark:before:bg-gray-700">
                  {msg.steps && msg.steps.length > 0 && (
                    <AgentThoughtBlock steps={msg.steps} />
                  )}

                  {msg.approval && (
                    <ApprovalCard
                      approval={msg.approval}
                      onResolved={() => undefined}
                    />
                  )}

                  {msg.error && (() => {
                    const ErrIcon = ERROR_ICON[msg.errorKind ?? "unknown"] ?? AlertTriangle;
                    return (
                      <div className="mb-2 rounded-xl border border-red-200 bg-red-50/70 dark:border-red-900/60 dark:bg-red-950/25 px-3.5 py-3 max-w-2xl animate-in fade-in slide-in-from-top-1">
                        <div className="flex items-start gap-2.5">
                          <ErrIcon className="w-4 h-4 text-red-500 dark:text-red-400 shrink-0 mt-0.5" />
                          <div className="min-w-0 flex-1">
                            <div className="text-xs font-medium text-red-800 dark:text-red-200">{msg.error}</div>
                            {msg.errorHint && (
                              <div className="text-[11px] text-red-600/80 dark:text-red-300/80 mt-1 leading-relaxed">{msg.errorHint}</div>
                            )}
                            <div className="flex items-center gap-2 mt-2.5">
                              {onRetry && (
                                <button
                                  type="button"
                                  disabled={!canRetry}
                                  onClick={() => onRetry(msg.id)}
                                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed text-white transition-colors cursor-pointer"
                                >
                                  <RotateCcw className="w-3 h-3" /> 重试
                                </button>
                              )}
                              {msg.content && (
                                <button
                                  type="button"
                                  onClick={copyContent}
                                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium text-red-700 dark:text-red-300 hover:bg-red-100/70 dark:hover:bg-red-900/40 transition-colors cursor-pointer"
                                >
                                  复制已生成内容
                                </button>
                              )}
                              {msg.errorRaw && (
                                <button
                                  type="button"
                                  onClick={() => setRawExpanded((v) => !v)}
                                  className="inline-flex items-center gap-1 text-[10px] text-red-500/70 dark:text-red-400/70 hover:text-red-600 dark:hover:text-red-300 cursor-pointer transition-colors"
                                >
                                  <ChevronDown className={`w-3 h-3 transition-transform ${rawExpanded ? "rotate-180" : ""}`} />
                                  技术详情
                                </button>
                              )}
                            </div>
                            {rawExpanded && msg.errorRaw && (
                              <pre className="mt-2 whitespace-pre-wrap break-words rounded-md bg-red-100/70 dark:bg-red-900/30 px-2 py-1.5 text-[10px] font-mono text-red-700 dark:text-red-300 max-h-32 overflow-auto">{msg.errorRaw}</pre>
                            )}
                            {sessionId && (
                              <button type="button" onClick={copyId} className="mt-2 inline-flex items-center gap-1 font-mono text-[10px] text-red-600/60 dark:text-red-400/60 hover:text-red-700 dark:hover:text-red-300 underline underline-dotted cursor-pointer" title="复制会话 ID 用于排障">
                                {copied ? <Check className="w-3 h-3" /> : null}{copied ? "已复制" : `会话 ID：${sessionId}`}
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })()}
                  {(msg.content || msg.isTyping) && (
                    <div className="relative animate-in fade-in">
                        <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-blue-50 dark:bg-blue-950/40 border border-blue-100 dark:border-blue-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)]">
                            <MessageSquare className="w-[10px] h-[10px] text-blue-500 dark:text-blue-400" />
                        </div>
                        <div className="text-sm text-foreground leading-relaxed pt-0.5">
                            {msg.content ? (
                              <Markdown className="chat-markdown">{msg.content}</Markdown>
                            ) : (
                              <span className="text-muted-foreground text-xs">正在思考…</span>
                            )}
                            {msg.isTyping && msg.content && <span className="inline-block w-1.5 h-4 ml-1 align-middle bg-blue-500 animate-pulse"></span>}
                        </div>
                        {/* 回答结束后的元信息行:工具次数 · tokens · 耗时 · 已停止标记 + 复制 */}
                        {!msg.isTyping && (
                          <div className="mt-1.5 flex items-center gap-3 group/meta">
                            <TurnMeta
                              steps={msg.steps}
                              durationMs={msg.turnMetrics?.durationMs}
                              usage={msg.turnMetrics?.usage}
                            />
                            {msg.stopped && (
                              <span className="text-[10px] text-amber-600 dark:text-amber-400">· 已停止</span>
                            )}
                            {msg.content && (
                              <button
                                type="button"
                                onClick={copyContent}
                                className="text-[10px] text-muted-foreground/0 group-hover/meta:text-muted-foreground/70 hover:!text-foreground transition-colors cursor-pointer"
                                title="复制回答"
                              >
                                复制
                              </button>
                            )}
                          </div>
                        )}
                    </div>
                  )}

                  {!msg.isTyping && msg.sources && msg.sources.length > 0 && (
                    <SourceCitations sources={msg.sources} />
                  )}

                  {!msg.isTyping && msg.content && (
                    <div className="relative pt-1">
                      <SaveToKnowledgeButton msg={msg} />
                    </div>
                  )}

              </div>
          </div>
      </div>
    </>
  );
}
