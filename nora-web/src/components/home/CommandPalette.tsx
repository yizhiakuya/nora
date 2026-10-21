'use client';

import { useState, useMemo, useRef, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Search, Home, Folder, MessageSquare, BookOpen, Zap, Database, Server, ListCheck, Settings, File, FileCode, Cpu, ArrowRight, Plug, Bot } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useFiles } from "@/hooks/useFiles";
import { useConnections } from "@/hooks/useConnections";
import { useServices } from "@/hooks/useServices";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useAutomations } from "@/hooks/useAutomations";
import { searchSessions, type SessionSummary } from "@/lib/api/agentApi";

interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
}

interface SearchResult {
  id: string;
  group: string;
  label: string;
  hint: string;
  href: string;
  icon: React.ElementType;
}

const NAV_PAGES: SearchResult[] = [
  { id: "nav-home",    group: "页面", label: "助手",       hint: "输入需求、继续处理、最近成果", href: "/",       icon: Home },
  { id: "nav-files",   group: "页面", label: "资料",       hint: "文件、长期知识、任务结果",   href: "/files",   icon: Folder },
  { id: "nav-chat",    group: "页面", label: "对话",       hint: "AI 对话助手",    href: "/chat",           icon: MessageSquare },
  { id: "nav-sessions", group: "页面", label: "全部会话", hint: "搜索标题、ID 和消息正文", href: "/sessions", icon: MessageSquare },
  { id: "nav-tasks",   group: "页面", label: "任务",       hint: "正在处理 / 定期任务 / 执行记录", href: "/tasks", icon: ListCheck },
  { id: "nav-know",    group: "页面", label: "长期知识",   hint: "资料 · RAG 检索", href: "/knowledge",      icon: BookOpen },
  { id: "nav-agent",   group: "页面", label: "Agent",      hint: "默认行为、权限、指令与记忆", href: "/agent", icon: Bot },
  { id: "nav-skills",  group: "页面", label: "技能",       hint: "Agent · 可复用的处理方法", href: "/agent?section=skills", icon: Zap },
  { id: "nav-mcp",     group: "页面", label: "连接与工具", hint: "Agent · MCP 服务器", href: "/agent?section=connections", icon: Plug },
  { id: "nav-models",  group: "页面", label: "模型",       hint: "Agent · LLM 服务商接入", href: "/agent?section=model", icon: Cpu },
  { id: "nav-ds",      group: "页面", label: "数据源",     hint: "数据库连接与查询（高级）", href: "/data-sources", icon: Database },
  { id: "nav-env",     group: "页面", label: "环境控制台", hint: "服务与日志（高级）", href: "/environments",   icon: Server },
  { id: "nav-envvars", group: "页面", label: "环境变量",   hint: "Agent · 自定义凭据", href: "/agent?section=env", icon: Server },
  { id: "nav-set",     group: "页面", label: "设置",       hint: "外观、通知与隐私",  href: "/settings",      icon: Settings },
];

export function CommandPalette({ isOpen, onClose }: CommandPaletteProps) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [searchError, setSearchError] = useState<string>();
  const [searching, setSearching] = useState(false);
  const resultsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const abort = new AbortController();
    setSessions([]); setSearchError(undefined);
    const q = query.trim();
    setSearching(Boolean(q));
    if (!q) return;
    const timer = setTimeout(() => {
      void searchSessions(q, 0, 6, abort.signal).then(page => { if (!abort.signal.aborted) setSessions(page.items); })
        .catch(error => { if (!abort.signal.aborted) setSearchError(error instanceof Error ? error.message : String(error)); })
        .finally(() => { if (!abort.signal.aborted) setSearching(false); });
    }, 250);
    return () => { clearTimeout(timer); abort.abort(); };
  }, [query]);
  const files = useFiles((s) => s.files);
  const docs = useKnowledgeDocs((s) => s.docs);
  const automations = useAutomations((s) => s.rules);
  const connections = useConnections((s) => s.connections);
  const services = useServices((s) => s.services);

  const index = useMemo<SearchResult[]>(() => [
    ...NAV_PAGES,
    ...files.map((f) => ({
      id: `file-${f.id}`, group: "文件", label: f.name, hint: `${f.type} · ${f.size}`, href: `/files?viewer=${encodeURIComponent(`file:${f.id}`)}`, icon: File,
    })),
    ...docs.map((d) => ({
      id: `doc-${d.id}`, group: "知识库", label: d.name, hint: `${d.chunks} chunks · ${d.source}`, href: `/files?view=knowledge&doc=${d.id}`, icon: d.source === "repo" ? FileCode : BookOpen,
    })),
    ...connections.map((c) => ({
      id: `conn-${c.id}`, group: "数据源", label: c.name, hint: `${c.engine} · ${c.host}:${c.port || "—"}`, href: "/data-sources", icon: Database,
    })),
    ...services.map((s) => ({
      id: `svc-${s.id}`, group: "服务", label: s.name, hint: `:${s.port} · ${s.status}`, href: "/environments", icon: Server,
    })),
    ...automations.map((a) => ({
      id: `auto-${a.id}`, group: "任务", label: a.name, hint: a.trigger, href: "/tasks", icon: Zap,
    })),
  ], [files, docs, automations, connections, services]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = q ? index.filter((r) => r.label.toLowerCase().includes(q) || r.hint.toLowerCase().includes(q)) : NAV_PAGES;
    return [...sessions.map(session => ({ id: `session-${session.id}`, group: "会话", label: session.title || "新对话", hint: `${session.messageCount} 条消息`, href: `/chat?session=${encodeURIComponent(session.id)}`, icon: MessageSquare })), ...list.slice(0, 12)];
  }, [index, query, sessions]);

  const grouped = useMemo(() => {
    const map = new Map<string, SearchResult[]>();
    for (const r of results) {
      const arr = map.get(r.group) ?? [];
      arr.push(r);
      map.set(r.group, arr);
    }
    return Array.from(map.entries());
  }, [results]);

  if (!isOpen) return null;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="快速查找"
      width="w-[600px]"
      footer={<div className="flex w-full items-center justify-between text-[11px] text-muted-foreground"><span>↑ ↓ 选择 · Enter 打开</span><span>Esc 关闭</span></div>}
      onOpenAutoFocus={(event) => { event.preventDefault(); inputRef.current?.focus(); }}
    >
      <div onKeyDown={event => {
        if (event.nativeEvent.isComposing) return;
        const buttons = Array.from(resultsRef.current?.querySelectorAll<HTMLButtonElement>("[data-search-result]") ?? []);
        const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
        if (event.key === "ArrowDown") { event.preventDefault(); buttons[Math.min(current + 1, buttons.length - 1)]?.focus(); }
        if (event.key === "ArrowUp") { event.preventDefault(); if (current <= 0) inputRef.current?.focus(); else buttons[current - 1]?.focus(); }
        if (event.key === "Enter" && document.activeElement === inputRef.current && results[0]) { event.preventDefault(); navigate(results[0].href); onClose(); }
      }}>
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
        <Input
          ref={inputRef}
          aria-label="搜索工作台"
          placeholder="搜索会话正文、已载入资料和页面…"
          maxLength={200}
          className="h-11 pl-9 text-base sm:text-sm bg-muted/40 border-border"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <div ref={resultsRef} className="mt-4 max-h-[50dvh] space-y-3 overflow-y-auto custom-scroll">
        {searchError && <p role="alert" className="text-sm whitespace-pre-wrap break-words">{searchError}</p>}
        {searching && <p role="status" className="text-xs text-muted-foreground">正在搜索会话…</p>}
        {grouped.length === 0 && !searching && !searchError && (
          <div className="py-10 text-center text-sm text-muted-foreground">
            没有匹配「{query}」的结果
          </div>
        )}
        {grouped.map(([group, items]) => (
          <div key={group}>
            <div className="px-3 py-2 text-[11px] font-semibold text-muted-foreground">{group}</div>
            {items.map((r) => (
              <Button
                key={r.id}
                data-search-result
                variant="ghost"
                className="group h-auto w-full justify-start gap-3 px-3 py-2.5 text-left"
                onClick={() => { navigate(r.href); onClose(); setQuery(""); }}
              >
                <r.icon className="w-4 h-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium">{r.label}</span>
                  <span className="mt-0.5 block truncate text-xs font-normal text-muted-foreground">{r.hint}</span>
                </span>
                <ArrowRight className="w-3.5 h-3.5 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100" />
              </Button>
            ))}
          </div>
        ))}
        {query.trim() && <Button variant="outline" className="w-full" onClick={() => { navigate(`/sessions?q=${encodeURIComponent(query.trim())}`); onClose(); }}>查看全部匹配会话</Button>}
      </div>
      </div>
    </Modal>
  );
}
