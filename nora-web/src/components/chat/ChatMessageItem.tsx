import { Database, MessageSquare, BookOpen, FileCode, Server, FileText, Check, Copy, RotateCcw, ChevronDown, AlertTriangle, Pencil, X, Clock, Play } from "lucide-react";
import { useState } from "react";
import { AgentProcessBlock, TurnMeta } from "./AgentThoughtBlock";
import { ApprovalCard } from "./ApprovalCard";
import { QuestionCard } from "./QuestionCard";
import { ChatMessage } from "@/lib/api/chatApi";
import { splitChatRefs, refKey } from "@/lib/chatRefs";
import { RefChip } from "./RefChip";
import { Markdown } from "@/components/shared/Markdown";
import { toast } from "sonner";
import { useChatSessions } from "@/hooks/useChatSessions";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";
import { FileDeliveryCards } from "./FileDeliveryCards";
import { FileChangeCards } from "./FileChangeCards";
import { humanizeError } from "@/lib/errorMessages";
import { Button } from "@/components/ui/button";
import { SaveToKnowledgeButton, SaveAsFileButton } from "./MessageSaveActions";
import { contentKey } from "@/lib/saveState";
import { CompactionMessage } from "./CompactionMessage";

const SOURCE_ICON: Record<string, React.ElementType> = {
  file: FileText,
  database: Database,
  repo: FileCode,
  environment: Server,
  chat: MessageSquare,
  text: MessageSquare,
};

/**
 * 低置信度阈值:与后端 nora.retrieval.min-score(0.45)保持同一数值。
 * 低于它的结果本不该出现(后端 floor 已滤),出现即说明后端配置漂移,
 * 前端照常标黄提醒;两处数值必须一起改。
 */
const LOW_SCORE_THRESHOLD = 0.45;

/**
 * 引用来源：默认折叠为一行(避免长片段挤占回答空间),点击展开看片段详情。
 * 与思考块一致:ChevronDown 旋转表示开合,展开后带最大高度与滚动。
 */
function SourceCitations({ sources }: { sources: NonNullable<ChatMessage["sources"]> }) {
  const [open, setOpen] = useState(false);
  // 折叠态用去重后的文档名做摘要,多个 chunk 命中同一文档时不重复罗列
  const docNames = Array.from(new Set(sources.map((s) => s.docName).filter(Boolean)));
  const hasLowScore = sources.some((s) => s.score < LOW_SCORE_THRESHOLD);

  return (
    <div className="relative animate-in fade-in slide-in-from-bottom-2">
      <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-purple-50 dark:bg-purple-950/40 border border-purple-100 dark:border-purple-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)] dark:shadow-[0_0_0_2px_rgba(17,24,39,1)]">
        <BookOpen className="w-[10px] h-[10px] text-purple-500 dark:text-purple-400" />
      </div>
      <div className="pt-0.5">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="group flex items-center gap-1.5 w-full text-left cursor-pointer"
        >
          <span className="text-[10px] text-muted-foreground group-hover:text-foreground transition-colors shrink-0">
            引用来源 · {sources.length} 个片段
          </span>
          {hasLowScore && (
            <span className="text-[10px] text-amber-600 dark:text-amber-400 shrink-0">· 含低置信度内容</span>
          )}
          {!open && docNames.length > 0 && (
            <span className="text-[10px] text-muted-foreground/70 truncate min-w-0">
              {docNames.join("、")}
            </span>
          )}
          <ChevronDown className={`w-3 h-3 text-muted-foreground/40 transition-transform shrink-0 ${open ? "" : "-rotate-90"}`} />
        </button>
        {open && (
          <div className="space-y-2 mt-2 max-h-80 overflow-auto">
            {sources.map((s, i) => {
              const Icon = SOURCE_ICON[s.source] ?? FileText;
              // 用户引用(📎/📄/@)注入的是整篇内容(可达 8K/12K 字符),UI 只展示摘要;
              // 模型侧收到的是完整注入内容(后端 systemPromptWith 拼装)
              const isRef = s.snippet.startsWith("【用户引用");
              const snippet = isRef && s.snippet.length > 600
                ? s.snippet.slice(0, 600) + `…(共 ${s.snippet.length} 字符,已注入完整内容)`
                : s.snippet;
              return (
                <div key={i} className="bg-card border border-border rounded-xl p-3 relative overflow-hidden">
                  <div className="absolute left-0 top-0 bottom-0 w-1 bg-purple-500" style={{ opacity: s.score }} />
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-1.5 min-w-0">
                      <Icon className="w-3 h-3 text-purple-500 dark:text-purple-400 shrink-0" />
                      <span className="text-xs font-medium text-foreground truncate">{s.docName}</span>
                      <span className="text-[9px] text-muted-foreground shrink-0">{isRef ? "用户引用" : `chunk #${s.chunkIndex}`}</span>
                    </div>
                    <span className={`text-[9px] font-bold ${s.score < LOW_SCORE_THRESHOLD ? "text-amber-600 bg-amber-50 dark:text-amber-400 dark:bg-amber-950/40" : "text-purple-600 bg-purple-50 dark:text-purple-400 dark:bg-purple-950/40"} px-1.5 py-0.5 rounded-full tabular-nums shrink-0`}>
                      {(s.score * 100).toFixed(0)}%
                    </span>
                  </div>
                  <p className="text-[11px] text-muted-foreground leading-relaxed whitespace-pre-wrap">{snippet}</p>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

export function ChatMessageItem(props: React.ComponentProps<typeof ConversationMessage>) {
  return props.msg.sender === "compaction" ? <CompactionMessage {...props} /> : <ConversationMessage {...props} />;
}

function ConversationMessage({ msg, sessionId: sessionIdProp, onRetry, canRetry = true, onEdit, canEdit = true }: { msg: ChatMessage; /** 所属会话 id(由 ChatConversation 传入,权威值);不传时回落 store.activeId */ sessionId?: string; onRetry?: (msgId: string) => void; canRetry?: boolean; onEdit?: (msgId: string, edited: string) => void | Promise<void>; canEdit?: boolean }) {
  // 保存状态键必须用**渲染该消息的会话 id**(与 ChatConversation 一致)——
  // 此前用 store.activeId,与 active.id(回落 sessions[0])可能不同,
  // 导致保存时与刷新后的键不一致、状态恢复失败(2026-09-21 实测)
  const storeActiveId = useChatSessions((s) => s.activeId);
  const sessionId = sessionIdProp ?? storeActiveId;
  const [editing, setEditing] = useState(false);
  const [editDraft, setEditDraft] = useState("");
  const [editBusy, setEditBusy] = useState(false);
  const submitEdit = async () => {
    if (!onEdit || !editDraft.trim() || !canEdit || editBusy) return;
    setEditBusy(true);
    try { await onEdit(msg.id, editDraft); setEditing(false); }
    catch (failure) {
      const friendly = humanizeError(failure);
      toast.error(friendly.message, { description: friendly.hint });
    }
    finally { setEditBusy(false); }
  };
  // 流式期间实时秒表(每秒递增);结束后停表不再显示(总耗时走 TurnMeta)
  const elapsedSeconds = useElapsedSeconds(msg.startedAtMs, !!msg.isTyping);
  const hasTimelineText = msg.steps?.some(step => step.type === "commentary" || step.type === "answer");
  const answerStep = msg.steps?.find(step => step.type === "answer");
  const displayContent = hasTimelineText || msg.turnMetrics?.status === "interrupted"
    ? answerStep?.detail ?? "" : msg.content;

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
        <div className="group/user flex gap-2 animate-in fade-in duration-200">
            <div className="flex-1"></div>
            {editing ? (
              <div className="bg-background border border-blue-400 dark:border-blue-600 rounded-2xl rounded-tr-sm p-3 max-w-[80%] w-full">
                <textarea
                  autoFocus
                  rows={Math.min(6, Math.max(1, editDraft.split('\n').length))}
                  value={editDraft}
                  disabled={editBusy}
                  onChange={(e) => setEditDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      void submitEdit();
                    } else if (e.key === 'Escape') {
                      if (!editBusy) setEditing(false);
                    }
                  }}
                  className="w-full bg-transparent resize-none outline-none text-sm text-foreground custom-scroll"
                />
                <div className="flex justify-end gap-1.5 mt-1.5">
                  <button type="button" disabled={editBusy} onClick={() => setEditing(false)} className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer">
                    <X className="w-3 h-3" /> 取消
                  </button>
                  <button
                    type="button"
                    disabled={!editDraft.trim() || !canEdit || editBusy}
                    onClick={() => void submitEdit()}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors cursor-pointer"
                  >
                    <Check className="w-3 h-3" /> 重新发送
                  </button>
                </div>
              </div>
            ) : (
              <div className="bg-muted min-w-0 px-4 py-3 rounded-2xl text-[14px] leading-7 max-w-[90%] break-words">
                {/* 发送者标记(2026-09-20,定时任务=往会话发消息):该消息由定时任务
                    触发写入,不是用户手输——用户看历史时能明确区分 */}
                {msg.sender === "steering" && (
                  <div className="mb-1.5 text-[10px] font-medium text-muted-foreground">补充要求</div>
                )}
                {msg.sender === "automation" && (
                  <div className="flex items-center gap-1 mb-1.5 text-[10px] font-medium text-amber-700 dark:text-amber-300">
                    <Clock className="w-3 h-3" /> 定时任务
                  </div>
                )}
                {(() => {
                  // 引用行拆成 chips 展示(文件/知识库/技能/MCP 工具),正文保留原样换行
                  const { body, refs } = splitChatRefs(msg.content);
                  return (
                    <>
                      {refs.length > 0 && (
                        <div className="flex flex-wrap gap-1.5 mb-2">
                          {refs.map((ref) => (
                            <RefChip key={refKey(ref)} chatRef={ref} />
                          ))}
                        </div>
                      )}
                      {body && <span className="whitespace-pre-wrap">{body}</span>}
                    </>
                  );
                })()}
              </div>
            )}
            <div className="flex items-start pt-3">
              {onEdit && canEdit && !editing && (
                <button
                  type="button"
                  title="编辑并重新发送(之后的对话将一并回退)"
                  onClick={() => { setEditDraft(msg.content); setEditing(true); }}
                  aria-label="编辑并重新发送"
                  className="rounded p-1 text-muted-foreground hover:text-foreground md:opacity-0 md:group-hover/user:opacity-100 md:group-focus-within/user:opacity-100 focus-visible:opacity-100 transition-opacity"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="flex animate-in fade-in duration-200">
          <div className="min-w-0 flex-1 overflow-hidden">
              <div className="text-[13px] font-semibold flex items-center gap-2 mb-3"><img src="/brand-logo.png" alt="" width={18} height={18} />Nora <span className="text-[11px] text-muted-foreground/70 font-normal">{msg.timestamp}</span>
                {msg.isTyping && elapsedSeconds != null && (
                  <span className="text-[10px] font-normal tabular-nums text-blue-600 dark:text-blue-400" title="本轮流式响应已用时">{elapsedSeconds}s</span>
                )}
              </div>
              
              <div className="space-y-4">
                  {msg.steps && msg.steps.length > 0 && (
                    <AgentProcessBlock
                      steps={msg.steps.filter(step => step.type !== "answer" && !(msg.error && step.id === "s-error"))}
                      isTyping={!!msg.isTyping && !answerStep}
                      isRunning={(msg.isRunning ?? !!msg.isTyping) && !answerStep}
                      durationMs={msg.turnMetrics?.durationMs}
                    />
                  )}

                  {msg.approval && (
                    <ApprovalCard
                      approval={msg.approval}
                      onResolved={(d) => toast.success(d === "approved" ? "已批准，Agent 继续执行" : "已拒绝，Agent 跳过该操作")}
                    />
                  )}

                  {msg.question && (
                    <QuestionCard
                      question={msg.question}
                      onAnswered={(answer) => toast.success(`已回答：${answer.length > 30 ? answer.slice(0, 30) + "…" : answer}`)}
                    />
                  )}

                  <FileDeliveryCards msg={msg} sessionId={sessionId} />

                  <FileChangeCards msg={msg} sessionId={sessionId} />

                  {msg.turnMetrics?.status === "interrupted" && (
                    <div role="status" className="rounded-lg border border-border bg-muted/40 p-3 space-y-2">
                      <p className="text-xs text-foreground">服务重启，任务已中断，已保存的工作过程已恢复。</p>
                      <p className="text-xs text-muted-foreground">继续后会复用已完成结果，并先核实结果未知的操作。</p>
                      {onRetry && (
                        <Button size="sm" variant="outline" disabled={!canRetry} onClick={() => onRetry(msg.id)}>
                          <Play /> 继续任务
                        </Button>
                      )}
                    </div>
                  )}

                  {msg.error && (
                      <div role="alert" className="mb-2 rounded-xl border border-red-200 bg-red-50/70 dark:border-red-900/60 dark:bg-red-950/25 px-3.5 py-3 max-w-2xl animate-in fade-in slide-in-from-top-1">
                        <div className="flex items-start gap-2.5">
                          <AlertTriangle className="w-4 h-4 text-red-500 dark:text-red-400 shrink-0 mt-0.5" />
                          <div className="min-w-0 flex-1">
                            <pre className="whitespace-pre-wrap break-words text-xs text-red-800 dark:text-red-200 max-h-64 overflow-auto">{msg.errorRaw || msg.error}</pre>
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
                            </div>
                          </div>
                        </div>
                      </div>
                  )}
                  {(displayContent || (msg.isTyping && !hasTimelineText) || msg.stopped) && (
                    <div className="relative animate-in fade-in">
                        <div className="text-[14px] text-foreground leading-7 pt-0.5">
                            {displayContent ? (
                              <Markdown className="chat-markdown" sessionId={sessionId ?? undefined}>{displayContent}</Markdown>
                            ) : msg.isTyping ? (
                              <span className="text-muted-foreground text-xs">正在思考…{elapsedSeconds != null ? ` ${elapsedSeconds}s` : ""}</span>
                            ) : (
                              <span className="text-muted-foreground text-xs">{hasTimelineText ? "已停止生成，已保留工作过程。" : "已停止生成，未产生内容。可重新编辑发送。"}</span>
                            )}
                            {msg.isTyping && displayContent && <span className="inline-block w-1.5 h-4 ml-1 align-middle bg-blue-500 animate-pulse"></span>}
                        </div>
                        {/* 回答结束后的元信息行:工具次数 · tokens · 耗时 · 已停止标记 + 复制 */}
                        {!msg.isTyping && (
                          <div className="mt-1.5 flex items-center gap-3 group/meta">
                            <TurnMeta
                              steps={msg.steps}
                              durationMs={msg.turnMetrics?.durationMs}
                              usage={msg.turnMetrics?.usage}
                              ttftMs={msg.turnMetrics?.ttftMs}
                              hideToolAndDuration={!!msg.steps && msg.steps.length > 0}
                            />
                            {msg.stopped && (
                              <span className="text-[10px] text-amber-600 dark:text-amber-400">· 已停止</span>
                            )}
                          </div>
                        )}
                    </div>
                  )}

                  {!msg.isTyping && msg.sources && msg.sources.length > 0 && (
                    <SourceCitations sources={msg.sources} />
                  )}

                  {!msg.isTyping && msg.content && (
                    <div className="-ml-2 flex items-center gap-0.5 flex-wrap">
                      <Button type="button" variant="ghost" size="sm" className="h-8 px-2 text-xs text-muted-foreground" onClick={copyContent} title="复制回答"><Copy className="h-3.5 w-3.5" />复制</Button>
                      {displayContent && <>
                        <SaveToKnowledgeButton key={`knowledge:${contentKey(sessionId, msg.content)}`} msg={msg} sessionId={sessionId} />
                        {!msg.steps?.some(step => step.result?.files?.length) && <SaveAsFileButton key={`file:${contentKey(sessionId, msg.content)}`} msg={msg} sessionId={sessionId} />}
                      </>}
                    </div>
                  )}

              </div>
          </div>
      </div>
    </>
  );
}
