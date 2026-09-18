import { useRef, useEffect, useState, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Paperclip, FileText, AtSign, Layers, ChevronDown, Send, Square, Zap, Check, Settings, Brain, ShieldAlert, Loader2, BookOpen, CornerDownLeft, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSkills } from "@/hooks/useSkills";
import { useModelProviders, resolveDefaultProvider, REASONING_LEVELS } from "@/hooks/useModelProviders";
import { PERMISSION_MODE_META, type PermissionMode } from "@/lib/api/chatApi";
import type { ChatRef } from "@/lib/chatRefs";
import { refKey } from "@/lib/chatRefs";
import { ReferencePicker } from "./ReferencePicker";
import { RefChip } from "./RefChip";
import { filesApi } from "@/lib/services/filesApi";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useMcpServers } from "@/hooks/useMcpServers";
import { USE_BACKEND } from "@/lib/api/client";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "sonner";

interface ChatInputAreaProps {
  input: string;
  setInput: (value: string) => void;
  isSending: boolean;
  onSend: () => void;
  /** 停止生成:中断当前流式响应(流式期间发送按钮变停止按钮) */
  onStop?: () => void;
  contextTokens?: number;
  contextLimit?: number;
  /** 对话框选的思考等级;undefined = 跟随设置页该模型默认 */
  reasoningLevel?: string;
  onReasoningLevelChange?: (level: string | undefined) => void;
  /** 权限模式:请求批准 / 帮我批准 / 完全访问权限 */
  permissionMode?: PermissionMode;
  onPermissionModeChange?: (mode: PermissionMode) => void;
  /** 待发送引用(📎附件/📄文件/@知识库/技能/MCP 工具) */
  refs?: ChatRef[];
  onAddRef?: (ref: ChatRef) => void;
  /** 移除引用(键 = chatRefs.refKey) */
  onRemoveRef?: (key: string) => void;
}

/** @ 提及菜单条目(引用 + 来源标签) */
type MentionItem = ChatRef & { hint: string };

const MENTION_MAX_ITEMS = 8;

export function ChatInputArea({ input, setInput, isSending, onSend, onStop, contextTokens = 0, contextLimit = 128000, reasoningLevel, onReasoningLevelChange, permissionMode = "assist", onPermissionModeChange, refs = [], onAddRef, onRemoveRef }: ChatInputAreaProps) {
  const navigate = useNavigate();
  const skills = useSkills((s) => s.skills);
  const toggleSkill = useSkills((s) => s.toggleSkill);
  const providers = useModelProviders((s) => s.providers);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const defaultProviderId = useModelProviders((s) => s.defaultProviderId);
  const setDefaultModel = useModelProviders((s) => s.setDefaultModel);
  const files = useFiles((s) => s.files);
  const docs = useKnowledgeDocs((s) => s.docs);
  const mcpServers = useMcpServers((s) => s.servers);
  const syncMcpServers = useMcpServers((s) => s.syncServers);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  /** 📎 上传中(禁用按钮防重复) */
  const [uploading, setUploading] = useState(false);
  /** 引用选择器:file=📄 文件中心 / doc=@ 知识库;null=关闭 */
  const [picker, setPicker] = useState<"file" | "doc" | null>(null);
  /** @ 内联提及:光标前的触发符与查询串;null=菜单关闭 */
  const [mention, setMention] = useState<{ trigger: "@" | "#" | "/"; query: string; start: number } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  /** 拖拽文件悬停中(高亮提示) */
  const [dragging, setDragging] = useState(false);
  const contextPercent = Math.min(100, Math.round((contextTokens / contextLimit) * 100));

  // 当前生效渠道:显式 id 优先、失效时按模型名回落(与后端同一顺序)。
  // 勾选判定用「渠道 + 模型名」双匹配——同名模型跨渠道时只勾选真实生效的那一条。
  const activeProvider = resolveDefaultProvider(providers, defaultModel, defaultProviderId);
  const configuredLevels = activeProvider?.modelSettings?.[defaultModel]?.reasoningLevels ?? [];
  const availableLevels = configuredLevels.length
    ? REASONING_LEVELS.filter((l) => configuredLevels.includes(l))
    : REASONING_LEVELS;

  const handleToggle = (id: number, name: string, enabled: boolean) => {
    toggleSkill(id);
    toast.success(`能力「${name}」已${enabled ? "停用" : "启用"}，AI ${enabled ? "不再" : "现在"}可以使用它`);
  };

  /**
   * 📎 附件:选本地文件 → 上传文件中心 → 作为 file 引用加入待发送区。
   * 上传成功后同步文件中心列表(引用要能被 read_file 工具读到,必须服务端可见)。
   */
  const handlePickLocalFile = async (file: globalThis.File) => {
    if (uploading) return;
    setUploading(true);
    try {
      const item = await filesApi.uploadFile(file);
      useFiles.getState().syncFile(item);
      onAddRef?.({ kind: "file", id: item.id, name: item.name, size: item.size });
      toast.success(`「${item.name}」已上传并引用,发送后 AI 可读取`);
    } catch (e) {
      toast.error(`上传失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setUploading(false);
    }
  };

  /** 拖拽上传:依次上传多个文件(失败不阻断后续) */
  const handleDropFiles = async (dropped: globalThis.File[]) => {
    if (uploading || dropped.length === 0) return;
    setUploading(true);
    try {
      for (const file of dropped) {
        try {
          const item = await filesApi.uploadFile(file);
          useFiles.getState().syncFile(item);
          onAddRef?.({ kind: "file", id: item.id, name: item.name, size: item.size });
        } catch (e) {
          toast.error(`「${file.name}」上传失败：${e instanceof Error ? e.message : String(e)}`);
        }
      }
      toast.success(dropped.length > 1 ? `${dropped.length} 个文件已上传并引用` : `「${dropped[0].name}」已上传并引用`);
    } finally {
      setUploading(false);
    }
  };

  // ---- 内联提及(三触发符,对齐 Claude Code/Codex 约定) ----

  // 提及菜单打开时同步技能 / MCP 服务器与工具清单(对话页不依赖其他页面先访问)
  const mentionOpen = mention !== null;
  useEffect(() => {
    if (!mentionOpen) return;
    // "/" 需要技能与 MCP;"#" 用知识库(打开即拉最新)
    if (mention?.trigger === "/") {
      void useSkills.getState().syncFromBackend();
    }
    if (mention?.trigger === "#") {
      void useKnowledgeDocs.getState().syncFromBackend();
    }
  }, [mentionOpen, mention?.trigger]);

  useEffect(() => {
    if (!mentionOpen || mention?.trigger !== "/" || !USE_BACKEND) return;
    void syncMcpServers();
  }, [mentionOpen, mention?.trigger, syncMcpServers]);

  /**
   * 输入变化时探测光标前的触发符与查询串。
   * 三触发符各司其职(对齐 Claude Code/Codex 习惯):
   *   @ = 文件中心文件; # = 知识库文档; / = 技能与 MCP 服务器(斜杠命令语义)
   * 触发符须位于行首/空白后;查询串遇空格即关闭(避免普通文本误触发)。
   */
  const detectMention = (value: string, caret: number) => {
    const before = value.slice(0, caret);
    // 找最靠后的触发符
    const atIdx = Math.max(before.lastIndexOf("@"), before.lastIndexOf("#"), before.lastIndexOf("/"));
    if (atIdx < 0) return null;
    if (atIdx > 0 && !/\s/.test(before[atIdx - 1])) return null; // 邮箱/URL 等场景不触发
    const trigger = before[atIdx] as "@" | "#" | "/";
    const query = before.slice(atIdx + 1);
    if (/\s/.test(query)) return null; // 查询串遇空格即关闭
    return { trigger, query, start: atIdx };
  };

  /**
   * 候选列表(按触发符分区,各自过滤):
   *   @ → 文件中心; # → 知识库文档; / → 技能 + MCP 工具。
   */
  const mentionItems: MentionItem[] = useMemo(() => {
    if (!mention) return [];
    const q = mention.query.trim().toLowerCase();
    const items: MentionItem[] = [];
    const push = (item: MentionItem) => {
      if (q && !item.name.toLowerCase().includes(q)) return;
      items.push(item);
    };
    if (mention.trigger === "@") {
      for (const f of files) {
        push({ kind: "file", id: f.id, name: f.name, size: f.size, hint: "文件" });
        if (items.length >= MENTION_MAX_ITEMS) break;
      }
    } else if (mention.trigger === "#") {
      for (const d of docs) {
        if (d.status !== "indexed") continue;
        push({ kind: "doc", id: d.id, name: d.name, hint: "知识库" });
        if (items.length >= MENTION_MAX_ITEMS) break;
      }
    } else {
      for (const s of skills) {
        push({ kind: "skill", id: s.id, name: s.name, hint: "技能" });
        if (items.length >= MENTION_MAX_ITEMS) return items;
      }
      // MCP 以服务器为单位引用(总标题),不展开单个工具——工具名太多且随远端变化
      for (const server of mcpServers) {
        if (!server.enabled || server.toolCount <= 0) continue;
        push({ kind: "mcp", id: server.id, name: server.name, hint: `MCP · ${server.toolCount} 个工具` });
        if (items.length >= MENTION_MAX_ITEMS) return items;
      }
    }
    return items;
  }, [mention, docs, skills, files, mcpServers]);

  // 查询变化时重置选中位
  useEffect(() => {
    setMentionIndex(0);
  }, [mention?.query, mention?.start]);

  /** 选中提及项:移除输入中的 @查询 文本,加入引用 chip,恢复焦点 */
  const selectMention = (item: MentionItem) => {
    if (!mention) return;
    const ta = textareaRef.current;
    const caret = ta?.selectionStart ?? input.length;
    const newValue = input.slice(0, mention.start) + input.slice(caret);
    setInput(newValue);
    onAddRef?.(item);
    setMention(null);
    const pos = mention.start;
    requestAnimationFrame(() => {
      ta?.focus();
      ta?.setSelectionRange(pos, pos);
    });
  };

  // 监听 input 变化，动态调整 textarea 高度
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    // 强制先重置为 auto 以便在删除文字时能缩回
    textarea.style.height = 'auto';
    // 设置为滚动高度，最大限制交给 CSS maxHeight
    textarea.style.height = `${textarea.scrollHeight}px`;
  }, [input]);

  return (
    <div className="absolute bottom-0 left-0 right-0 p-6 bg-gradient-to-t from-[#f4f5f7] via-[#f4f5f7] to-transparent dark:from-gray-950 dark:via-gray-950 pointer-events-none">
      <div className="max-w-3xl mx-auto pointer-events-auto">
          {/* AI 能力状态条：启用=高亮，停用=置灰；点击切换 */}
          <div className="flex items-center gap-1.5 mb-2 flex-wrap">
            <span className="inline-flex items-center gap-1 text-[10px] text-muted-foreground mr-1">
              <Zap className="w-3 h-3" /> AI 能力
            </span>
            {skills.map((skill) => (
              <button
                key={skill.id}
                type="button"
                onClick={() => handleToggle(skill.id, skill.name, skill.enabled)}
                title={skill.desc}
                className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium border cursor-pointer transition-colors ${skill.enabled ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800" : "bg-muted text-muted-foreground border-border line-through opacity-60"}`}
              >
                <span className={`w-1.5 h-1.5 rounded-full ${skill.enabled ? "bg-blue-500" : "bg-gray-300 dark:bg-gray-700"}`} />
                {skill.name}
              </button>
            ))}
          </div>

          <div className="relative">
          {/* 内联提及菜单(@ 文件 / # 知识库 / / 技能与工具):绝对定位在输入框上方,不挤压布局 */}
          {mention && mentionItems.length > 0 && (
            <div className="absolute bottom-full left-0 right-0 mb-2 z-20 rounded-xl border border-border bg-card shadow-lg overflow-hidden animate-in fade-in slide-in-from-bottom-1">
              <div className="px-3 py-1.5 text-[10px] text-muted-foreground border-b border-border/60 flex items-center justify-between">
                <span>
                  {mention.trigger === "@" ? "引用文件" : mention.trigger === "#" ? "引用知识库文档" : "技能与 MCP 工具"}
                  {mention.query ? `（筛选「${mention.query}」）` : ""}
                </span>
                <span className="flex items-center gap-1 text-muted-foreground/60">
                  <CornerDownLeft className="w-2.5 h-2.5" /> 选择 · Esc 关闭
                </span>
              </div>
              <ul className="max-h-64 overflow-y-auto custom-scroll">
                {mentionItems.map((item, i) => (
                  <li key={refKey(item)}>
                    <button
                      type="button"
                      onMouseDown={(e) => { e.preventDefault(); selectMention(item); }}
                      onMouseEnter={() => setMentionIndex(i)}
                      className={`w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors cursor-pointer ${
                        i === mentionIndex ? "bg-blue-50 dark:bg-blue-950/40" : "hover:bg-muted/70"
                      }`}
                    >
                      {item.kind === "doc" ? (
                        <BookOpen className={`w-3.5 h-3.5 shrink-0 ${i === mentionIndex ? "text-purple-500 dark:text-purple-400" : "text-muted-foreground"}`} />
                      ) : item.kind === "skill" ? (
                        <Zap className={`w-3.5 h-3.5 shrink-0 ${i === mentionIndex ? "text-green-500 dark:text-green-400" : "text-muted-foreground"}`} />
                      ) : item.kind === "mcp" ? (
                        <Wrench className={`w-3.5 h-3.5 shrink-0 ${i === mentionIndex ? "text-orange-500 dark:text-orange-400" : "text-muted-foreground"}`} />
                      ) : (
                        <FileText className={`w-3.5 h-3.5 shrink-0 ${i === mentionIndex ? "text-blue-500 dark:text-blue-400" : "text-muted-foreground"}`} />
                      )}
                      <span className="flex-1 min-w-0 text-xs text-foreground truncate">{item.name}</span>
                      <span className="text-[10px] text-muted-foreground shrink-0">{item.hint}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {/* 有查询但无匹配:轻提示(按触发符给对应去处) */}
          {mention && mention.query && mentionItems.length === 0 && (
            <div className="absolute bottom-full left-0 right-0 mb-2 z-20 rounded-xl border border-border bg-card shadow-lg px-3 py-2.5 text-[11px] text-muted-foreground animate-in fade-in slide-in-from-bottom-1">
              {mention.trigger === "@"
                ? <>没有匹配「{mention.query}」的文件（可去「文件」页导入）</>
                : mention.trigger === "#"
                  ? <>没有匹配「{mention.query}」的知识库文档（可去「知识库」页导入并索引）</>
                  : <>没有匹配「{mention.query}」的技能或 MCP 工具（可去「AI 能力」/「MCP」页配置）</>}
            </div>
          )}

          <div
            className={`border rounded-2xl bg-card shadow-sm transition-all flex flex-col overflow-hidden relative group ${
              dragging
                ? "border-blue-500 ring-4 ring-blue-500/15"
                : "border-border focus-within:border-blue-500 focus-within:ring-4 focus-within:ring-blue-500/10"
            }`}
            onDragOver={(e) => { e.preventDefault(); if (!dragging) setDragging(true); }}
            onDragLeave={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
            }}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              const dropped = Array.from(e.dataTransfer.files ?? []);
              if (dropped.length) void handleDropFiles(dropped);
            }}
          >
              {/* 拖拽悬停提示层 */}
              {dragging && (
                <div className="absolute inset-0 z-10 bg-blue-500/5 flex items-center justify-center pointer-events-none">
                  <span className="text-xs font-medium text-blue-600 dark:text-blue-400 flex items-center gap-1.5">
                    <Paperclip className="w-3.5 h-3.5" /> 松开上传到文件中心并引用
                  </span>
                </div>
              )}
              {/* 待发送引用 chips(📎附件/📄文件/@知识库/技能/MCP 工具):发送时随消息序列化 */}
              {refs.length > 0 && (
                <div className="flex flex-wrap gap-1.5 px-3 pt-2.5">
                  {refs.map((ref) => (
                    <RefChip key={refKey(ref)} chatRef={ref} onRemove={onRemoveRef} />
                  ))}
                </div>
              )}
              <textarea
                ref={textareaRef}
                rows={1}
                style={{ minHeight: '52px', maxHeight: '240px' }}
                placeholder="给“AI 助理”发送消息...（@ 文件 · # 知识库 · / 技能与工具）"
                className="w-full bg-transparent resize-none outline-none text-sm px-4 pt-4 pb-2 text-foreground placeholder:text-muted-foreground overflow-y-auto custom-scroll"
                value={input}
                onChange={(e) => {
                  const value = e.target.value;
                  setInput(value);
                  // @ 提及探测:光标位置为准(支持在文本中间引用)
                  const caret = e.target.selectionStart ?? value.length;
                  setMention(detectMention(value, caret));
                }}
                onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    // @ 菜单打开时优先响应导航/选择键
                    if (mention && mentionItems.length > 0) {
                      if (e.key === 'ArrowDown') {
                        e.preventDefault();
                        setMentionIndex((i) => (i + 1) % mentionItems.length);
                        return;
                      }
                      if (e.key === 'ArrowUp') {
                        e.preventDefault();
                        setMentionIndex((i) => (i - 1 + mentionItems.length) % mentionItems.length);
                        return;
                      }
                      if (e.key === 'Enter' || e.key === 'Tab') {
                        e.preventDefault();
                        selectMention(mentionItems[mentionIndex]);
                        return;
                      }
                      if (e.key === 'Escape') {
                        e.preventDefault();
                        setMention(null);
                        return;
                      }
                    }
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        if ((input.trim() || refs.length > 0) && !isSending) {
                            onSend();
                        }
                    }
                }}
                onBlur={() => {
                  // 焦点离开:收起 @ 菜单(点选走 mousedown,不受影响)
                  setMention(null);
                }}
              ></textarea>

              <div className="flex justify-between items-end p-2.5 pt-1">
                  <div className="flex shrink-0 gap-0.5">
                      {/* 📎 附件:选本地文件上传到文件中心并引用 */}
                      <input
                        ref={fileInputRef}
                        type="file"
                        multiple
                        className="hidden"
                        onChange={(e) => {
                          const picked = Array.from(e.target.files ?? []);
                          e.target.value = ""; // 允许连续选同一文件
                          if (picked.length === 1) void handlePickLocalFile(picked[0]);
                          else if (picked.length > 1) void handleDropFiles(picked);
                        }}
                      />
                      <Button
                        variant="ghost" size="icon"
                        className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"
                        onClick={() => fileInputRef.current?.click()}
                        disabled={uploading}
                        title="上传附件(存入文件中心并引用,AI 可读取)"
                      >
                        {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Paperclip className="w-4 h-4" />}
                      </Button>
                      {/* 📄 引用文件中心的文件 */}
                      <Button
                        variant="ghost" size="icon"
                        className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"
                        onClick={() => setPicker("file")}
                        title="引用文件中心的文件(AI 用 read_file 读取)"
                      >
                        <FileText className="w-4 h-4" />
                      </Button>
                      {/* @ 引用知识库文档 */}
                      <Button
                        variant="ghost" size="icon"
                        className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"
                        onClick={() => setPicker("doc")}
                        title="引用知识库文档(回答优先参考)"
                      >
                        <AtSign className="w-4 h-4" />
                      </Button>
                  </div>

                  <div className="flex min-w-0 flex-1 justify-end gap-1.5 items-center">
                      <div className="hidden sm:flex items-center gap-1.5 px-1.5 py-1 rounded-md text-[10px] text-muted-foreground font-medium hover:bg-muted cursor-pointer shrink-0" title={`上下文用量 ${contextPercent}%`}>
                          <Layers className={`w-3 h-3 shrink-0 ${contextPercent >= 90 ? "text-red-500" : contextPercent >= 75 ? "text-amber-500" : "text-muted-foreground"}`} />
                          <div className="hidden lg:flex w-12 h-1.5 bg-muted rounded-full overflow-hidden shrink-0">
                              <div className={`h-full ${contextPercent >= 90 ? "bg-red-500" : contextPercent >= 75 ? "bg-amber-500" : "bg-blue-500"}`} style={{width: `${contextPercent}%`}}></div>
                          </div>
                          <span className={`font-mono ${contextPercent >= 90 ? "text-red-500" : contextPercent >= 75 ? "text-amber-500" : ""}`}>{contextTokens >= 1000 ? `${Math.round(contextTokens / 1000)}k` : contextTokens}/{contextLimit >= 1000 ? `${Math.round(contextLimit / 1000)}k` : contextLimit}</span>
                      </div>

                      <div className="w-px h-3 bg-gray-200 dark:bg-gray-800 shrink-0"></div>

                      {/* 思考等级:仅列出设置页为当前模型启用的等级(窄屏隐藏,减拥挤) */}
                      {onReasoningLevelChange && availableLevels.length > 0 && (
                        <div className="hidden sm:flex items-center">
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-muted-foreground hover:text-foreground" title="思考等级">
                              <Brain className="w-3 h-3 mr-1 shrink-0" />
                              {reasoningLevel ?? "自动"} <ChevronDown className="w-3 h-3 ml-1 shrink-0 text-muted-foreground" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-40 rounded-xl">
                            <DropdownMenuLabel className="text-xs text-muted-foreground">思考等级</DropdownMenuLabel>
                            <DropdownMenuItem
                              className="flex items-center justify-between cursor-pointer text-xs"
                              onClick={() => {
                                onReasoningLevelChange(undefined);
                                toast.success("思考等级已设为自动（跟随模型默认）");
                              }}
                            >
                              <span>自动</span>
                              {reasoningLevel === undefined && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                            </DropdownMenuItem>
                            {availableLevels.map((level) => (
                              <DropdownMenuItem
                                key={level}
                                className="flex items-center justify-between cursor-pointer text-xs font-mono"
                                onClick={() => {
                                  onReasoningLevelChange(level);
                                  toast.success(`思考等级已设为 ${level}`);
                                }}
                              >
                                <span>{level}</span>
                                {reasoningLevel === level && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                              </DropdownMenuItem>
                            ))}
                          </DropdownMenuContent>
                        </DropdownMenu>
                        </div>
                      )}

                      <div className="hidden sm:block w-px h-3 bg-gray-200 dark:bg-gray-800 shrink-0"></div>

                      {/* 三档权限模式 */}
                      {onPermissionModeChange && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="sm"
                              className={`h-7 text-xs px-2 shrink-0 ${permissionMode === "full" ? "text-orange-600 dark:text-orange-400" : "text-muted-foreground hover:text-foreground"}`}
                              title="权限模式"
                            >
                              <ShieldAlert className="w-3 h-3 sm:mr-1 shrink-0" />
                              <span className="hidden sm:inline truncate">{PERMISSION_MODE_META[permissionMode].label}</span>
                              <ChevronDown className="w-3 h-3 ml-1 shrink-0 text-muted-foreground" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-72 rounded-xl p-1.5">
                            <DropdownMenuLabel className="px-2 py-1.5 text-xs text-muted-foreground">权限模式</DropdownMenuLabel>
                            {(Object.keys(PERMISSION_MODE_META) as PermissionMode[]).map((mode) => {
                              const meta = PERMISSION_MODE_META[mode];
                              const active = permissionMode === mode;
                              return (
                                <DropdownMenuItem
                                  key={mode}
                                  className={`items-start gap-2.5 rounded-lg py-2.5 cursor-pointer ${mode === "full" ? "text-orange-600 dark:text-orange-400 focus:text-orange-600 dark:focus:text-orange-400" : ""}`}
                                  onClick={() => {
                                    onPermissionModeChange(mode);
                                    toast.success(`权限模式已切换为「${meta.label}」`);
                                  }}
                                >
                                  <ShieldAlert className={`w-4 h-4 mt-0.5 shrink-0 ${mode === "full" ? "text-orange-500" : "text-muted-foreground"}`} />
                                  <span className="flex-1 min-w-0">
                                    <span className="block text-xs font-medium">{meta.label}</span>
                                    <span className="block text-[10px] leading-relaxed text-muted-foreground mt-0.5 whitespace-normal">{meta.description}</span>
                                  </span>
                                  {active && <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" />}
                                </DropdownMenuItem>
                              );
                            })}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}

                      <div className="hidden sm:block w-px h-3 bg-gray-200 dark:bg-gray-800 shrink-0"></div>

                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-muted-foreground hover:text-foreground min-w-0 shrink max-w-[100px] sm:max-w-none"
                            title={activeProvider ? `当前渠道：${activeProvider.name}` : undefined}>
                            <span className="truncate">{defaultModel}</span> <ChevronDown className="w-3 h-3 ml-1 shrink-0 text-muted-foreground" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-64 rounded-xl max-h-96 overflow-y-auto">
                          <DropdownMenuLabel className="text-xs text-muted-foreground">切换当前模型</DropdownMenuLabel>
                          {/* 按渠道分组:同名模型跨渠道时展示各自来源,勾选只落生效的一条 */}
                          {providers
                            .filter((p) => p.enabled && p.models.length > 0)
                            .map((p, idx) => (
                              <div key={p.id}>
                                {idx > 0 && <DropdownMenuSeparator />}
                                <DropdownMenuLabel className="text-[10px] text-muted-foreground/80 py-1">{p.name}</DropdownMenuLabel>
                                {p.models.map((m) => {
                                  const isActive = activeProvider?.id === p.id && m === defaultModel;
                                  return (
                                    <DropdownMenuItem
                                      key={`${p.id}-${m}`}
                                      className="flex items-center justify-between cursor-pointer text-xs"
                                      onClick={() => {
                                        setDefaultModel(m, p.id);
                                        toast.success(`已切换默认模型为 ${m}（${p.name}）`);
                                      }}
                                    >
                                      <span className="truncate">{m}</span>
                                      {isActive && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400 shrink-0" />}
                                    </DropdownMenuItem>
                                  );
                                })}
                              </div>
                            ))}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            className="text-xs text-blue-600 dark:text-blue-400 cursor-pointer flex items-center gap-1.5"
                            onClick={() => navigate("/settings?tab=模型管理")}
                          >
                            <Settings className="w-3 h-3" /> 管理模型服务商...
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>

                      {/* 流式期间发送按钮变停止按钮(调研:frontendpatterns.dev/stop-generation) */}
                      <Button
                        size="icon"
                        className={`shrink-0 w-8 h-8 rounded-lg ml-1 transition-colors ${
                          isSending
                            ? "bg-foreground hover:bg-foreground/80 text-background"
                            : "bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white"
                        }`}
                        onClick={isSending ? onStop : onSend}
                        disabled={!isSending && !input.trim() && refs.length === 0}
                        title={isSending ? "停止生成" : "发送"}
                      >
                          {isSending ? <Square className="w-3 h-3 fill-current" /> : <Send className="w-3.5 h-3.5 ml-0.5" />}
                      </Button>
                  </div>
              </div>
          </div>
          </div>
      </div>

      {/* 引用选择器:📄 文件中心 / @ 知识库(外层容器 pointer-events-none,须显式恢复) */}
      <div className="pointer-events-auto">
        <ReferencePicker
          kind={picker ?? "file"}
          isOpen={picker !== null}
          onClose={() => setPicker(null)}
          onPick={(ref) => onAddRef?.(ref)}
        />
      </div>
  </div>
  );
}
