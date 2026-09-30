import { Sparkles, Database, MessageSquare, BookOpen, FileCode, Server, FileText, Check, RotateCcw, ChevronDown, AlertTriangle, Pencil, X, FileDown, Loader2, Clock } from "lucide-react";
import { useState } from "react";
import { AgentProcessBlock, TurnMeta } from "./AgentThoughtBlock";
import { ApprovalCard } from "./ApprovalCard";
import { QuestionCard } from "./QuestionCard";
import { ChatMessage } from "@/lib/api/chatApi";
import { splitChatRefs, refKey } from "@/lib/chatRefs";
import { RefChip } from "./RefChip";
import { Markdown } from "@/components/shared/Markdown";
import { toast } from "sonner";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { saveTextAsync } from "@/lib/services/ragService";
import { workspaceApi } from "@/lib/services/workspaceApi";
import { useChatSessions } from "@/hooks/useChatSessions";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";
import { USE_BACKEND } from "@/lib/api/client";
import { contentKey, contentHashSuffix, getSavedRecord, markSaved } from "@/lib/saveState";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { FileDeliveryCards } from "./FileDeliveryCards";
import { FileChangeCards } from "./FileChangeCards";
import { parseArtifactsFence } from "@/lib/artifacts";
import { useFileViewer } from "@/hooks/useFileViewer";

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

function SaveToKnowledgeButton({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  // 已保存状态按 (会话+内容哈希) 持久化:刷新/切屏后恢复(2026-09-21 修复重复保存)
  const key = contentKey(sessionId, msg.content);
  const [saved, setSaved] = useState(() => getSavedRecord(key).knowledgeSaved === true);
  const addChatDoc = useKnowledgeDocs((s) => s.addChatDoc);

  const handleSave = async () => {
    if (saved) return;
    const title = `对话结论 · ${msg.content.slice(0, 24).replace(/[#*\n]/g, "").trim()}`;
    // 本地始终留底;后端模式再真实入库(name-keyed 同名覆盖,可在知识库检索)
    addChatDoc(`${title}…`, msg.content);
    let docId: number | null = null;
    if (USE_BACKEND) {
      try {
        const doc = await saveTextAsync(title, msg.content);
        docId = doc?.id ?? null;
      } catch (e) {
        toast.error(`入库失败：${(e as Error).message}`);
        return;
      }
    }
    setSaved(true);
    markSaved(key, { knowledgeSaved: true, knowledgeDocId: docId ?? undefined });
    // B1(2026-09-27):服务端登记归属(来自哪次对话)——换浏览器也能从
    // 资料页找到;登记失败静默(保存本身已成功,登记是增益)
    if (USE_BACKEND && docId != null) {
      void savedArtifactsApi.register({
        kind: "knowledge_doc",
        path: String(docId),
        name: title,
        sessionId,
        messageKey: key,
      });
    }
    toast.success(USE_BACKEND ? "已入库，可在知识库检索" : "已保存到本地知识库");
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

/**
 * 「保存为文件」(M2-03,2026-09-20,方案 §4.1 S1):
 * 把回答写成工作区真实 Markdown 文件(返回验证过的路径),与「保存到知识库」
 * 是两个不同动作(分别说明):文件=可下载/可引用的资产;知识库=可被检索。
 *
 * 2026-09-21 修复重复生成:① 已保存状态按 (会话+内容哈希) 持久化,刷新/切屏
 * 后恢复;② 文件名用内容哈希后缀替代时间戳——即使状态意外丢失再点,也只是
 * 覆盖同一文件,不再产生副本(双保险)。
 */
function SaveAsFileButton({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  const key = contentKey(sessionId, msg.content);
  const [savedPath, setSavedPath] = useState<string | null>(
    () => getSavedRecord(key).filePath ?? null
  );
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (savedPath) { void useFileViewer.getState().openTargets([`workspace:${savedPath}`], undefined, { sessionId }); return; }
    if (saving) return;
    if (!USE_BACKEND) {
      toast.info("保存为文件需要连接后端服务");
      return;
    }
    setSaving(true);
    try {
      // 文件名:首行标题(去 Markdown 标记)+ 内容哈希后缀——同一回答重复保存
      // 命中同一路径(覆盖),不因时间戳变化产生副本
      const legacyReport = parseArtifactsFence(msg.content)?.find(gallery => gallery.gallery === "text" && typeof gallery.data.text === "string");
      const body = legacyReport ? String(legacyReport.data.text) : msg.content;
      const firstLine = body.split("\n").map((l) => l.replace(/^#+\s*/, "").trim()).find((l) => l.length > 0) ?? "回答";
      const base = firstLine.replace(/[\\/:*?"<>|]/g, "").slice(0, 40) || "回答";
      const path = `reports/${base}-${contentHashSuffix(msg.content)}.md`;
      await workspaceApi.writeFile(path, body);
      // 写后回读验证(方案要求"返回验证过的路径",不空口报成功)
      const check = await workspaceApi.readFile(path);
      if (check !== body) {
        throw new Error("写入后校验不一致");
      }
      setSavedPath(path);
      markSaved(key, { filePath: path });
      // B1(2026-09-27):服务端登记归属(来自哪次对话)——换浏览器也能从
      // 资料页找到;登记失败静默(保存本身已成功,登记是增益)
      void savedArtifactsApi.register({
        kind: "workspace_file",
        path,
        name: firstLine.slice(0, 60) || "回答",
        sessionId,
        messageKey: key,
      });
      toast.success(`已保存:工作区 ${path}`, { description: "可在「资料 → 工作区」中打开" });
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <button
      type="button"
      onClick={handleSave}
      disabled={saving}
      className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${savedPath ? "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800" : "bg-card text-muted-foreground border-border hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700"}`}
      title={savedPath ? `已保存:${savedPath}` : "保存为工作区 Markdown 文件(可下载/引用)"}
    >
      {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : savedPath ? <Check className="w-3 h-3" /> : <FileDown className="w-3 h-3" />}
      {savedPath ? "已保存为文件" : "保存为文件"}
    </button>
  );
}

export function ChatMessageItem({ msg, sessionId: sessionIdProp, onRetry, canRetry = true, onEdit, canEdit = true }: { msg: ChatMessage; /** 所属会话 id(由 ChatConversation 传入,权威值);不传时回落 store.activeId */ sessionId?: string; onRetry?: (msgId: string) => void; canRetry?: boolean; onEdit?: (msgId: string, edited: string) => void; canEdit?: boolean }) {
  // 保存状态键必须用**渲染该消息的会话 id**(与 ChatConversation 一致)——
  // 此前用 store.activeId,与 active.id(回落 sessions[0])可能不同,
  // 导致保存时与刷新后的键不一致、状态恢复失败(2026-09-21 实测)
  const storeActiveId = useChatSessions((s) => s.activeId);
  const sessionId = sessionIdProp ?? storeActiveId;
  const [copied, setCopied] = useState(false);
  const [rawExpanded, setRawExpanded] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editDraft, setEditDraft] = useState("");
  // 流式期间实时秒表(每秒递增);结束后停表不再显示(总耗时走 TurnMeta)
  const elapsedSeconds = useElapsedSeconds(msg.startedAtMs, !!msg.isTyping);

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
        <div className="group/user flex gap-4 animate-in fade-in slide-in-from-bottom-2">
            <div className="flex-1"></div>
            {editing ? (
              <div className="bg-background border border-blue-400 dark:border-blue-600 rounded-2xl rounded-tr-sm p-3 max-w-[80%] w-full">
                <textarea
                  autoFocus
                  rows={Math.min(6, Math.max(1, editDraft.split('\n').length))}
                  value={editDraft}
                  onChange={(e) => setEditDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      if (editDraft.trim() && onEdit) { onEdit(msg.id, editDraft); setEditing(false); }
                    } else if (e.key === 'Escape') {
                      setEditing(false);
                    }
                  }}
                  className="w-full bg-transparent resize-none outline-none text-sm text-foreground custom-scroll"
                />
                <div className="flex justify-end gap-1.5 mt-1.5">
                  <button type="button" onClick={() => setEditing(false)} className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer">
                    <X className="w-3 h-3" /> 取消
                  </button>
                  <button
                    type="button"
                    disabled={!editDraft.trim() || !canEdit}
                    onClick={() => { if (onEdit && editDraft.trim()) { onEdit(msg.id, editDraft); setEditing(false); } }}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors cursor-pointer"
                  >
                    <Check className="w-3 h-3" /> 重新发送
                  </button>
                </div>
              </div>
            ) : (
              <div className="bg-background p-4 rounded-2xl rounded-tr-sm border border-border text-sm leading-relaxed max-w-[80%]">
                {/* 发送者标记(2026-09-20,定时任务=往会话发消息):该消息由定时任务
                    触发写入,不是用户手输——用户看历史时能明确区分 */}
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
            <div className="flex flex-col items-center gap-1">
              <div className="w-8 h-8 rounded-full flex-shrink-0 bg-blue-500 text-white text-[10px] font-bold flex items-center justify-center shadow-sm">NC</div>
              {onEdit && canEdit && !editing && (
                <button
                  type="button"
                  title="编辑并重新发送(之后的对话将一并回退)"
                  onClick={() => { setEditDraft(msg.content); setEditing(true); }}
                  className="opacity-0 group-hover/user:opacity-100 transition-opacity text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 cursor-pointer"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
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
              <div className="text-sm font-medium flex items-center gap-2 mb-4">AI 助理 <span className="text-[10px] text-muted-foreground font-normal">{msg.timestamp}</span>
                {msg.isTyping && elapsedSeconds != null && (
                  <span className="text-[10px] font-normal tabular-nums text-blue-600 dark:text-blue-400" title="本轮流式响应已用时">{elapsedSeconds}s</span>
                )}
              </div>
              
              <div className="relative pl-6 space-y-3 before:absolute before:inset-y-2 before:left-2.5 before:w-px before:bg-gray-200 dark:before:bg-gray-700">
                  {msg.steps && msg.steps.length > 0 && (
                    <AgentProcessBlock
                      steps={msg.steps}
                      isTyping={!!msg.isTyping}
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
                  {(msg.content || msg.isTyping || msg.stopped) && (
                    <div className="relative animate-in fade-in">
                        <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-blue-50 dark:bg-blue-950/40 border border-blue-100 dark:border-blue-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)]">
                            <MessageSquare className="w-[10px] h-[10px] text-blue-500 dark:text-blue-400" />
                        </div>
                        <div className="text-sm text-foreground leading-relaxed pt-0.5">
                            {msg.content ? (
                              <Markdown className="chat-markdown" sessionId={sessionId ?? undefined}>{msg.content}</Markdown>
                            ) : msg.isTyping ? (
                              <span className="text-muted-foreground text-xs">正在思考…{elapsedSeconds != null ? ` ${elapsedSeconds}s` : ""}</span>
                            ) : (
                              <span className="text-muted-foreground text-xs">已停止生成，未产生内容。可重新编辑发送。</span>
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
                              ttftMs={msg.turnMetrics?.ttftMs}
                              hideToolAndDuration={!!msg.steps && msg.steps.length > 0}
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
                    <div className="relative pt-1 flex items-center gap-2 flex-wrap">
                      <SaveToKnowledgeButton msg={msg} sessionId={sessionId} />
                      {!msg.steps?.some(step => step.result?.files?.length) && <SaveAsFileButton msg={msg} sessionId={sessionId} />}
                    </div>
                  )}

              </div>
          </div>
      </div>
    </>
  );
}
