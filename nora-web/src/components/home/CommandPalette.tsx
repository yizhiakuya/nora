'use client';

import { useState, useMemo, useRef } from "react";
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
  const files = useFiles((s) => s.files);
  const docs = useKnowledgeDocs((s) => s.docs);
  const automations = useAutomations((s) => s.rules);
  const connections = useConnections((s) => s.connections);
  const services = useServices((s) => s.services);

  const index = useMemo<SearchResult[]>(() => [
    ...NAV_PAGES,
    ...files.map((f) => ({
      id: `file-${f.id}`, group: "文件", label: f.name, hint: `${f.type} · ${f.size}`, href: "/files", icon: File,
    })),
    ...docs.slice(0, 20).map((d) => ({
      id: `doc-${d.id}`, group: "知识库", label: d.name, hint: `${d.chunks} chunks · ${d.source}`, href: "/knowledge", icon: d.source === "repo" ? FileCode : BookOpen,
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
    return list.slice(0, 12);
  }, [index, query]);

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
      onOpenAutoFocus={(event) => { event.preventDefault(); inputRef.current?.focus(); }}
    >
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
        <Input
          ref={inputRef}
          aria-label="搜索工作台"
          placeholder="搜索文件、知识库、数据源、服务、任务…"
          className="pl-9 text-base sm:text-sm"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <div className="mt-4 max-h-[50dvh] space-y-3 overflow-y-auto custom-scroll">
        {grouped.length === 0 && (
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
      </div>
    </Modal>
  );
}
