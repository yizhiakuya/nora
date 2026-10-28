import { useRef, useEffect, useState, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Paperclip, FileText, AtSign, Layers, ChevronDown, CornerDownLeft, Zap, Check, Settings, Brain, ShieldAlert, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSkills } from "@/hooks/useSkills";
import { useModelProviders, resolveDefaultProvider, REASONING_LEVELS } from "@/hooks/useModelProviders";
import { PERMISSION_MODE_META, type PermissionMode } from "@/lib/api/chatApi";
import type { ChatRef } from "@/lib/chatRefs";
import { refKey } from "@/lib/chatRefs";
import { ReferencePicker } from "./ReferencePicker";
import { RefChip } from "./RefChip";
import { ChatMessageQueue } from "./ChatMessageQueue";
import { SessionProjectButton } from "./SessionProjectButton";
import { ExecutionModeButton } from "./ExecutionModeButton";
import { ChatSubmitButton } from "./ChatSubmitButton";
import type { QueuedChatMessage } from "@/types";
import { useChatAttachments } from "@/hooks/useChatAttachments";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useMcpServers } from "@/hooks/useMcpServers";
import { usePreferences } from "@/hooks/usePreferences";
import { useAgentSettings } from "@/hooks/useAgentSettings";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCheckboxItem,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "sonner";
import { ContextUsageMeter } from "./ContextUsageMeter";
import type { ContextMeter } from "@/lib/chatContext";
import { matchingChatCommands, type ChatSettingsMenu } from "@/lib/chatCommands";
import { ChatMentionMenu, type MentionItem } from "./ChatMentionMenu";
import { ChatCommandPanel } from "./ChatCommandPanel";
import type { ChatCommandPanel as CommandPanel } from "@/hooks/useChatCommands";
import { Popover, PopoverAnchor, PopoverContent } from "@/components/ui/popover";

interface ChatInputAreaProps {
  sessionId: string;
  commandPanel: CommandPanel | null;
  onCloseCommandPanel: () => void;
  input: string;
  setInput: (value: string) => void;
  isSending: boolean;
  isStopping?: boolean;
  isCompacting?: boolean;
  settingsMenu?: ChatSettingsMenu | null;
  onSettingsMenuChange?: (menu: ChatSettingsMenu | null) => void;
  queuedMessages: QueuedChatMessage[];
  processingQueuedId: string | null;
  onInsertQueued: (id: string) => void;
  onRemoveQueued: (id: string) => void;
  onRestoreQueued: (id: string) => void;
  onSend: () => void;
  /** 停止生成；运行中新消息进入队列。 */
  onStop?: () => void;
  contextMeter?: ContextMeter;
  /** 对话框选的思考等级;undefined = 跟随设置页该模型默认 */
  reasoningLevel?: string;
   onReasoningLevelChange?: (level: string | undefined) => void | Promise<boolean>;
  /** 权限模式:请求批准 / 帮我批准 / 完全访问权限 */
  permissionMode?: PermissionMode;
   onPermissionModeChange?: (mode: PermissionMode) => void | Promise<boolean>;
  /** 待发送引用(📎附件/📄文件/@知识库/技能/MCP 工具) */
  refs?: ChatRef[];
  onAddRef?: (ref: ChatRef) => void;
  /** 移除引用(键 = chatRefs.refKey) */
  onRemoveRef?: (key: string) => void;
  /** 限定检索范围(B4,2026-09-27):开启后本轮只从引用的知识文档召回 */
  limitToRefs?: boolean;
  onLimitToRefsChange?: (v: boolean) => void;
}

const MENTION_MAX_ITEMS = 8;

export function ChatInputArea({ sessionId, commandPanel, onCloseCommandPanel, input, setInput, isSending, isStopping = false, isCompacting = false, settingsMenu, onSettingsMenuChange, queuedMessages, processingQueuedId, onInsertQueued, onRemoveQueued, onRestoreQueued, onSend, onStop, contextMeter = { tokens: 0, limit: 128_000, windowConfigured: false, estimated: true }, reasoningLevel, onReasoningLevelChange, permissionMode = "assist", onPermissionModeChange, refs = [], onAddRef, onRemoveRef, limitToRefs = false, onLimitToRefsChange }: ChatInputAreaProps) {
  const reasoningSummary = usePreferences((s) => s.reasoningSummary);
  const setReasoningSummary = usePreferences((s) => s.setReasoningSummary);
  const settingsBusy = useAgentSettings(s => s.saving || s.loading);
  const navigate = useNavigate();
  const skills = useSkills((s) => s.skills);
  const enabledSkillCount = skills.filter(skill => skill.enabled).length;
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
  useEffect(() => { if (commandPanel) textareaRef.current?.focus(); }, [commandPanel]);
  const previousRefCount = useRef(refs.length);
  useEffect(() => {
    if (refs.length > previousRefCount.current) textareaRef.current?.focus();
    previousRefCount.current = refs.length;
  }, [refs.length]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { uploading, uploadFiles, handlePaste } = useChatAttachments(onAddRef);
  /** 引用选择器:file=📄 文件中心 / doc=@ 知识库;null=关闭 */
  const [picker, setPicker] = useState<"file" | "doc" | null>(null);
  /** @ 内联提及:光标前的触发符与查询串;null=菜单关闭 */
  const [mention, setMention] = useState<{ trigger: "@" | "#" | "/"; query: string; start: number } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  /** 拖拽文件悬停中(高亮提示) */
  const [dragging, setDragging] = useState(false);
  const hasDraft = !!input.trim() || refs.length > 0;

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
    if (!mentionOpen || mention?.trigger !== "/") return;
    void syncMcpServers();
  }, [mentionOpen, mention?.trigger, syncMcpServers]);

  /**
   * 输入变化时探测光标前的触发符与查询串。
   * 三触发符各司其职(对齐 Claude Code/Codex 习惯):
   *   @ = 文件中心文件; # = 知识库文档; / = 内置命令、技能与 MCP 服务器
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
    if (commandPanel === "help") return matchingChatCommands("").map(command => ({ kind: "command", name: command.name, hint: command.description, command }));
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
      if (!input.slice(0, mention.start).trim()) {
        items.push(...matchingChatCommands(q).map(command => ({ kind: "command" as const, name: command.name, hint: command.description, command })));
      }
      let references = 0;
      for (const s of skills) {
        const count = items.length;
        push({ kind: "skill", id: s.id, name: s.name, hint: "技能" });
        if (items.length > count) references++;
        if (references >= MENTION_MAX_ITEMS) return items;
      }
      // MCP 以服务器为单位引用(总标题),不展开单个工具——工具名太多且随远端变化
      for (const server of mcpServers) {
        if (!server.enabled || server.toolCount <= 0) continue;
        const count = items.length;
        push({ kind: "mcp", id: server.id, name: server.name, hint: `MCP · ${server.toolCount} 个工具` });
        if (items.length > count) references++;
        if (references >= MENTION_MAX_ITEMS) return items;
      }
    }
    return items;
  }, [commandPanel, mention, input, docs, skills, files, mcpServers]);

  const showMentionMenu = !!(mention || commandPanel === "help") && mentionItems.length > 0;
  const popoverOpen = !!commandPanel || !!mention && (mentionItems.length > 0 || !!mention.query);
  const mentionTitle = commandPanel === "help" ? "Agent 命令" : mention?.trigger === "@" ? "引用文件" : mention?.trigger === "#" ? "引用知识库文档" : "命令 · 技能 · MCP";

  // 查询变化时重置选中位
  useEffect(() => {
    setMentionIndex(0);
  }, [mention?.query, mention?.start, commandPanel, mentionItems.length]);

  /** 选中提及项:移除输入中的 @查询 文本,加入引用 chip,恢复焦点 */
  const selectMention = (item: MentionItem) => {
    if (!mention && commandPanel !== "help") return;
    const ta = textareaRef.current;
    const start = mention?.start ?? 0;
    const caret = mention ? ta?.selectionStart ?? input.length : input.length;
    const replacement = item.kind === "command" ? `/${item.name}${"argument" in item.command ? " " : ""}` : "";
    const newValue = input.slice(0, start) + replacement + input.slice(caret);
    setInput(newValue);
    if (item.kind !== "command") onAddRef?.(item);
    setMention(null);
    onCloseCommandPanel();
    const pos = start + replacement.length;
    requestAnimationFrame(() => {
      ta?.focus();
      ta?.setSelectionRange(pos, pos);
    });
  };

  // 监听 input 变化，动态调整 textarea 高度
  // 2026-09-29 修复(输入框卡在大高度不缩回):此前仅在 [input] 变化时重算,
  // 高度以 inline style 持久化——一旦某个时刻把它留在高值而后续没有 input
  // 变化(实测输入框停在最大高度、内容清空后仍是大框),就会永久卡住。
  // 现在:每次渲染后都重算(rAF 合并同帧多次调度;不短路——自愈正是目的)。
  // 不手工同步 value:受控组件的 DOM 更新交给 React,组合输入(IME)期间
  // 手写 value 会擦掉正在组合的文本。
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    const fit = () => {
      // 强制先重置为 auto 以便在删除文字时能缩回
      textarea.style.height = 'auto';
      // 设置为滚动高度，最大限制交给 CSS maxHeight
      textarea.style.height = `${textarea.scrollHeight}px`;
    };
    const raf = requestAnimationFrame(fit);
    // 宽度变化(窗口/侧栏缩放改变折行数)不触发 React 渲染:ResizeObserver 兜底
    let lastWidth = textarea.clientWidth;
    const ro = typeof ResizeObserver !== "undefined"
      ? new ResizeObserver(() => {
          if (textarea.clientWidth !== lastWidth) {
            lastWidth = textarea.clientWidth;
            fit();
          }
        })
      : null;
    ro?.observe(textarea);
    return () => {
      cancelAnimationFrame(raf);
      ro?.disconnect();
    };
  });

  return (
    <div className="shrink-0 px-4 pb-3 pt-3 bg-background pointer-events-none [@container(min-width:640px)]:px-6">
      <div className="max-w-[820px] mx-auto pointer-events-auto">
          <ChatMessageQueue messages={queuedMessages} isSending={isSending} isCompacting={isCompacting} processingId={processingQueuedId}
            onInsert={onInsertQueued} onRemove={onRemoveQueued} onRestore={(id) => {
              onRestoreQueued(id);
              requestAnimationFrame(() => textareaRef.current?.focus());
            }} />
          <Popover open={popoverOpen} onOpenChange={open => { if (!open) { setMention(null); onCloseCommandPanel(); } }}>
          <PopoverAnchor asChild>
          <div className="relative [container-type:inline-size]">
          <div
            className={`composer flex flex-col overflow-hidden relative group ${
              dragging
                ? "border-ring ring-2 ring-ring/15"
                : ""
            }`}
            onDragOver={(e) => { e.preventDefault(); if (!dragging) setDragging(true); }}
            onDragLeave={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
            }}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              const dropped = Array.from(e.dataTransfer.files ?? []);
              void uploadFiles(dropped);
            }}
          >
              {/* 拖拽悬停提示层 */}
              {dragging && (
                <div className="absolute inset-0 z-10 bg-blue-500/5 flex items-center justify-center pointer-events-none">
                  <span className="text-xs font-medium text-blue-600 dark:text-blue-400 flex items-center gap-1.5">
                    <Paperclip className="w-3.5 h-3.5" /> 松开上传到资料并引用
                  </span>
                </div>
              )}
              {/* 待发送引用 chips(📎附件/📄文件/@知识库/技能/MCP 工具):发送时随消息序列化 */}
              {refs.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5 px-3 pt-2.5">
                  {refs.map((ref) => (
                    <RefChip key={refKey(ref)} chatRef={ref} onRemove={onRemoveRef} />
                  ))}
                  {/* 限定检索(B4,2026-09-27):有知识库文档引用时可选——开启后
                      本轮只从这些文档召回;关闭(默认)= 引用排前,其余资料仍参与 */}
                  {onLimitToRefsChange && refs.some((r) => r.kind === "doc") && (
                    <button
                      type="button"
                      onClick={() => onLimitToRefsChange(!limitToRefs)}
                      title={limitToRefs
                        ? "本轮只从引用的知识文档中检索;点击恢复「全库检索 + 引用优先」"
                        : "开启后本轮只从引用的知识文档中检索,不混入其它资料"}
                      className={`inline-flex items-center gap-1 px-2 py-1 rounded-full text-[10px] font-medium border transition-colors cursor-pointer ${limitToRefs
                        ? "bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800"
                        : "bg-card text-muted-foreground border-dashed border-border hover:text-foreground hover:border-foreground/40"}`}
                    >
                      <Layers className="w-2.5 h-2.5" />
                      {limitToRefs ? "仅引用资料内检索" : "限定检索范围"}
                    </button>
                  )}
                </div>
              )}
              <textarea
                ref={textareaRef}
                rows={1}
                style={{ minHeight: '68px', maxHeight: 'min(200px, 25dvh)' }}
                placeholder={isSending ? "补充要求，发送后加入队列…" : "继续这项工作，或提出新的想法…"}
                aria-label="给 Nora 发消息"
                title="@ 引用文件 · # 引用知识库 · / 命令、技能与工具"
                aria-expanded={showMentionMenu}
                aria-controls={showMentionMenu ? "chat-mention-menu" : undefined}
                aria-activedescendant={showMentionMenu ? `chat-mention-${mentionIndex}` : undefined}
                className="block w-full bg-transparent resize-none outline-none text-base sm:text-sm leading-relaxed px-4 pt-4 pb-2 text-foreground placeholder:text-muted-foreground overflow-y-auto custom-scroll"
                value={input}
                onPaste={handlePaste}
                onChange={(e) => {
                  const value = e.target.value;
                  setInput(value);
                  onCloseCommandPanel();
                  // @ 提及探测:光标位置为准(支持在文本中间引用)
                  const caret = e.target.selectionStart ?? value.length;
                  setMention(detectMention(value, caret));
                }}
                onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if ((mention || commandPanel) && e.key === "Escape") { e.preventDefault(); setMention(null); onCloseCommandPanel(); return; }
                    // @ 菜单打开时优先响应导航/选择键
                    if (showMentionMenu) {
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
                    }
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        if ((input.trim() || refs.length > 0) && !uploading) {
                            onSend();
                        }
                    }
                }}
                onBlur={() => {
                  // 焦点离开:收起 @ 菜单(点选走 mousedown,不受影响)
                  setMention(null);
                }}
              ></textarea>

              {isSending && hasDraft && <div className="mx-3 mb-2 flex items-center justify-between gap-2 rounded-lg bg-muted/60 px-2.5 py-1.5">
                <span className="text-[11px] text-muted-foreground">当前任务结束后发送</span>
                <Button type="button" variant="ghost" size="sm" className="h-7 gap-1.5 px-2 text-xs" disabled={uploading} onClick={onSend} aria-label="加入队列" title="加入队列 · Enter">
                  <CornerDownLeft className="h-3.5 w-3.5" />加入队列
                </Button>
              </div>}

              <div className="flex justify-between items-center gap-1 px-2 pb-2 pt-0.5">
                  <div className="flex shrink-0 items-center gap-0.5">
                      {/* 📎 附件:选本地文件上传到文件中心并引用 */}
                      <input
                        ref={fileInputRef}
                        type="file"
                        multiple
                        className="hidden"
                        onChange={(e) => {
                          const picked = Array.from(e.target.files ?? []);
                          e.target.value = ""; // 允许连续选同一文件
                          void uploadFiles(picked);
                        }}
                      />
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground" disabled={uploading} title="添加附件与引用" aria-label="添加附件与引用">
                            {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Paperclip className="w-4 h-4" />}
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" side="top" className="rounded-xl">
                          <DropdownMenuItem onSelect={() => fileInputRef.current?.click()}><Paperclip />上传附件</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setPicker("file")}><FileText />引用资料里的文件</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setPicker("doc")}><AtSign />引用知识库文档</DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="sm" className="h-8 px-2 gap-1 text-xs text-muted-foreground [@container(max-width:480px)]:w-8 [@container(max-width:480px)]:px-0" title="技能与工作过程显示" aria-label={`能力与显示：${enabledSkillCount} 个技能，${reasoningSummary === "detailed" ? "详细" : "简洁"}过程`}>
                            <Zap /><span className="[@container(max-width:480px)]:hidden">能力</span>
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" side="top" className="w-64 max-w-[calc(100vw-24px)] max-h-[min(480px,var(--radix-dropdown-menu-content-available-height))] overflow-y-auto rounded-xl">
                          <DropdownMenuLabel className="text-xs">启用的技能</DropdownMenuLabel>
                          {skills.map(skill => <DropdownMenuCheckboxItem key={skill.id} checked={skill.enabled} onCheckedChange={() => handleToggle(skill.id, skill.name, skill.enabled)} onSelect={event => event.preventDefault()} title={skill.desc} className="text-xs cursor-pointer">
                            <span className="min-w-0 truncate">{skill.name}</span>
                          </DropdownMenuCheckboxItem>)}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem className="text-xs" onSelect={() => navigate("/agent?section=skills")}><Settings />管理技能</DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuLabel className="text-xs text-muted-foreground">工作过程显示</DropdownMenuLabel>
                          <DropdownMenuRadioGroup value={reasoningSummary} onValueChange={(value) => {
                            if (value === "concise" || value === "detailed") setReasoningSummary(value);
                          }}>
                            <DropdownMenuRadioItem value="concise" className="items-start text-xs">
                              <span>简洁<span className="block mt-0.5 text-[11px] text-muted-foreground">默认收起工具和思考详情</span></span>
                            </DropdownMenuRadioItem>
                            <DropdownMenuRadioItem value="detailed" className="items-start text-xs">
                              <span>详细<span className="block mt-0.5 text-[11px] text-muted-foreground">自动展开工具和思考详情</span></span>
                            </DropdownMenuRadioItem>
                          </DropdownMenuRadioGroup>
                        </DropdownMenuContent>
                      </DropdownMenu>
                      <ContextUsageMeter meter={contextMeter} />
                  </div>

                  <div className="flex min-w-0 flex-1 justify-end gap-1 items-center">
                      {/* 思考等级:仅列出设置页为当前模型启用的等级 */}
                      {onReasoningLevelChange && availableLevels.length > 0 && (
                        <div className="flex shrink-0 items-center">
                        <DropdownMenu open={settingsMenu === undefined ? undefined : settingsMenu === "reasoning"} onOpenChange={open => onSettingsMenuChange?.(open ? "reasoning" : null)}>
                          <DropdownMenuTrigger asChild>
                             <Button disabled={settingsBusy} variant="ghost" size="sm" className="h-8 gap-1 text-xs px-2 text-muted-foreground hover:text-foreground [@container(max-width:640px)]:w-8 [@container(max-width:640px)]:px-0" title={`思考等级：${reasoningLevel ?? "自动"}`} aria-label={`思考等级：${reasoningLevel ?? "自动"}`}>
                              <Brain className="w-3 h-3 shrink-0" />
                              <span className="[@container(max-width:640px)]:hidden">{reasoningLevel ?? "自动"}</span>
                              <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground [@container(max-width:640px)]:hidden" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-40 rounded-xl">
                            <DropdownMenuLabel className="text-xs text-muted-foreground">默认思考等级 · 后续请求生效</DropdownMenuLabel>
                            <DropdownMenuItem
                              className="flex items-center justify-between cursor-pointer text-xs"
                               onClick={async () => {
                                 if (await onReasoningLevelChange(undefined) !== false) toast.success("思考等级已设为自动（跟随模型默认）");
                              }}
                            >
                              <span>自动</span>
                              {reasoningLevel === undefined && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                            </DropdownMenuItem>
                            {availableLevels.map((level) => (
                              <DropdownMenuItem
                                key={level}
                                className="flex items-center justify-between cursor-pointer text-xs font-mono"
                                 onClick={async () => {
                                   if (await onReasoningLevelChange(level) !== false) toast.success(`思考等级已设为 ${level}`);
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

                      {/* 三档权限模式 */}
                      {onPermissionModeChange && (
                        <DropdownMenu open={settingsMenu === undefined ? undefined : settingsMenu === "permissions"} onOpenChange={open => onSettingsMenuChange?.(open ? "permissions" : null)}>
                          <DropdownMenuTrigger asChild>
                            <Button
                               disabled={settingsBusy} variant="ghost"
                              size="sm"
                              className={`h-8 gap-1 text-xs px-2 shrink-0 [@container(max-width:640px)]:w-8 [@container(max-width:640px)]:px-0 ${permissionMode === "full" ? "text-orange-600 dark:text-orange-400" : "text-muted-foreground hover:text-foreground"}`}
                              title={`权限模式：${PERMISSION_MODE_META[permissionMode].label}`}
                              aria-label={`权限模式：${PERMISSION_MODE_META[permissionMode].label}`}
                            >
                              <ShieldAlert className="w-3 h-3 shrink-0" />
                              <span className="truncate [@container(max-width:640px)]:hidden">{PERMISSION_MODE_META[permissionMode].label}</span>
                              <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground [@container(max-width:640px)]:hidden" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-72 rounded-xl p-1.5">
                            <DropdownMenuLabel className="px-2 py-1.5 text-xs text-muted-foreground">默认权限 · 后续请求生效</DropdownMenuLabel>
                            {(Object.keys(PERMISSION_MODE_META) as PermissionMode[]).map((mode) => {
                              const meta = PERMISSION_MODE_META[mode];
                              const active = permissionMode === mode;
                              return (
                                <DropdownMenuItem
                                  key={mode}
                                  className={`items-start gap-2.5 rounded-lg py-2.5 cursor-pointer ${mode === "full" ? "text-orange-600 dark:text-orange-400 focus:text-orange-600 dark:focus:text-orange-400" : ""}`}
                                   onClick={async () => {
                                     if (await onPermissionModeChange(mode) !== false) toast.success(`权限模式已切换为「${meta.label}」`);
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

                      <DropdownMenu open={settingsMenu === undefined ? undefined : settingsMenu === "model"} onOpenChange={open => onSettingsMenuChange?.(open ? "model" : null)}>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="sm" className="h-8 gap-1 text-xs px-2 text-muted-foreground hover:text-foreground min-w-0 max-w-[160px] [@container(max-width:480px)]:max-w-[120px]"
                            aria-label={`模型：${defaultModel}`} title={`模型：${defaultModel}${activeProvider ? ` · 当前渠道：${activeProvider.name}` : ""}`}>
                            <span className="truncate">{defaultModel}</span> <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-64 rounded-xl max-h-96 overflow-y-auto">
                          <DropdownMenuLabel className="text-xs text-muted-foreground">默认模型 · 后续请求生效</DropdownMenuLabel>
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
                            onClick={() => navigate("/agent?section=model")}
                          >
                            <Settings className="w-3 h-3" /> 管理模型服务商...
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>

                      <ChatSubmitButton running={isSending} stopping={isStopping} uploading={uploading} hasDraft={hasDraft} onSend={onSend} onStop={onStop} />
                  </div>
              </div>
          </div>
          </div>
          </PopoverAnchor>
          <PopoverContent collisionPadding={12} className="flex max-h-[min(480px,var(--radix-popover-content-available-height))] w-[min(480px,var(--radix-popover-trigger-width))] max-w-[calc(100vw-24px)] flex-col overflow-hidden rounded-xl border-border/60"
            role={showMentionMenu ? "presentation" : "dialog"}
            aria-label={showMentionMenu ? undefined : commandPanel === "context" ? "上下文" : commandPanel === "status" ? "会话状态" : "引用提示"}
            onOpenAutoFocus={event => event.preventDefault()} onCloseAutoFocus={event => event.preventDefault()}
            onEscapeKeyDown={() => textareaRef.current?.focus()}
            onInteractOutside={event => { if (event.target === textareaRef.current) event.preventDefault(); }}>
            {showMentionMenu ? <ChatMentionMenu items={mentionItems} selected={mentionIndex} title={mentionTitle} onSelect={selectMention} onHover={setMentionIndex} />
              : mention && mention.query ? <div className="px-4 py-3 text-xs text-muted-foreground">
                  {mention.trigger === "@" ? <>没有匹配「{mention.query}」的文件（可去「资料」页导入）</>
                    : mention.trigger === "#" ? <>没有匹配「{mention.query}」的知识库文档（可去「资料 → 长期知识」导入并索引）</>
                      : <>没有匹配「{mention.query}」的命令、技能或 MCP；发送时按普通消息处理</>}
                </div>
              : commandPanel && commandPanel !== "help" ? <ChatCommandPanel panel={commandPanel} onClose={() => { onCloseCommandPanel(); textareaRef.current?.focus(); }}
                  sessionId={sessionId} meter={contextMeter} isSending={isSending} isCompacting={isCompacting} queueCount={queuedMessages.length} permissionMode={permissionMode} reasoningLevel={reasoningLevel} /> : null}
          </PopoverContent>
          </Popover>
          <div className="mt-1.5 flex items-center justify-between gap-2 px-1">
            <SessionProjectButton sessionId={sessionId} disabled={isSending} />
            <ExecutionModeButton key={sessionId} sessionId={sessionId} />
            <span className="hidden sm:block shrink-0 text-[11px] text-muted-foreground/70">Enter {isSending ? "加入队列" : "发送"} · Shift + Enter 换行</span>
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
