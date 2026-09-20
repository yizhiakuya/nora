'use client';

import { useState, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Search, Home, Folder, MessageSquare, BookOpen, Zap, Database, Server, ListCheck, Settings, File, FileCode, Cpu, ArrowRight, Plug } from "lucide-react";
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
  color: string;
}

const NAV_PAGES: SearchResult[] = [
  { id: "nav-home",    group: "页面", label: "助手",       hint: "输入需求、继续处理、最近成果", href: "/",       icon: Home,      color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-files",   group: "页面", label: "资料",       hint: "文件、长期知识、已保存成果",   href: "/files",   icon: Folder,    color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-chat",    group: "页面", label: "对话",       hint: "AI 对话助手",    href: "/chat",           icon: MessageSquare, color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-tasks",   group: "页面", label: "任务",       hint: "正在处理 / 定期任务 / 执行记录", href: "/tasks", icon: ListCheck, color: "text-yellow-500 dark:text-yellow-400" },
  { id: "nav-know",    group: "页面", label: "长期知识",   hint: "资料 · RAG 检索", href: "/knowledge",      icon: BookOpen,  color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-skills",  group: "页面", label: "技能",       hint: "设置 · 可复用的处理方法", href: "/settings?section=skills", icon: Zap, color: "text-yellow-500 dark:text-yellow-400" },
  { id: "nav-mcp",     group: "页面", label: "连接与工具", hint: "设置 · MCP 服务器", href: "/settings?section=connections", icon: Plug, color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-models",  group: "页面", label: "模型",       hint: "设置 · LLM 服务商接入", href: "/settings?section=model", icon: Cpu, color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-ds",      group: "页面", label: "数据源",     hint: "数据库连接与查询（高级）", href: "/data-sources", icon: Database,  color: "text-purple-500 dark:text-purple-400" },
  { id: "nav-env",     group: "页面", label: "环境控制台", hint: "服务与日志（高级）", href: "/environments",   icon: Server,    color: "text-green-500 dark:text-green-400" },
  { id: "nav-envvars", group: "页面", label: "环境变量",   hint: "设置 · 自定义凭据", href: "/settings?section=env", icon: Server, color: "text-green-500 dark:text-green-400" },
  { id: "nav-set",     group: "页面", label: "设置",       hint: "偏好与模型配置",  href: "/settings",      icon: Settings,  color: "text-muted-foreground" },
];

export function CommandPalette({ isOpen, onClose }: CommandPaletteProps) {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const files = useFiles((s) => s.files);
  const docs = useKnowledgeDocs((s) => s.docs);
  const automations = useAutomations((s) => s.rules);
  const connections = useConnections((s) => s.connections);
  const services = useServices((s) => s.services);

  const index = useMemo<SearchResult[]>(() => [
    ...NAV_PAGES,
    ...files.map((f) => ({
      id: `file-${f.id}`, group: "文件", label: f.name, hint: `${f.type} · ${f.size}`, href: "/files", icon: File, color: f.color,
    })),
    ...docs.slice(0, 20).map((d) => ({
      id: `doc-${d.id}`, group: "知识库", label: d.name, hint: `${d.chunks} chunks · ${d.source}`, href: "/knowledge", icon: d.source === "repo" ? FileCode : BookOpen, color: "text-blue-500 dark:text-blue-400",
    })),
    ...connections.map((c) => ({
      id: `conn-${c.id}`, group: "数据源", label: c.name, hint: `${c.engine} · ${c.host}:${c.port || "—"}`, href: "/data-sources", icon: Database, color: "text-purple-500 dark:text-purple-400",
    })),
    ...services.map((s) => ({
      id: `svc-${s.id}`, group: "服务", label: s.name, hint: `:${s.port} · ${s.status}`, href: "/environments", icon: Server, color: "text-green-500 dark:text-green-400",
    })),
    ...automations.map((a) => ({
      id: `auto-${a.id}`, group: "任务", label: a.name, hint: a.trigger, href: "/tasks", icon: Zap, color: "text-yellow-500 dark:text-yellow-400",
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
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-20 bg-black/40 backdrop-blur-sm animate-in fade-in duration-100 px-4"
      onClick={onClose}
    >
      <div
        className="bg-card rounded-xl shadow-2xl w-full max-w-[600px] overflow-hidden animate-in slide-in-from-top-4 duration-200 border border-border"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center px-4 border-b border-border">
          <Search className="w-5 h-5 text-muted-foreground shrink-0" />
          <input
            autoFocus
            type="text"
            placeholder="搜索文件、知识库、数据源、服务、任务…"
            className="w-full bg-transparent border-none focus:outline-none p-4 text-sm text-foreground placeholder:text-muted-foreground"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <kbd className="hidden sm:inline-block border border-border rounded px-1.5 py-0.5 text-[10px] text-muted-foreground bg-muted shrink-0">ESC</kbd>
        </div>
        <div className="p-2 bg-muted/30 max-h-[400px] overflow-y-auto custom-scroll">
          {grouped.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground">
              没有匹配「{query}」的结果
            </div>
          )}
          {grouped.map(([group, items]: [string, SearchResult[]]) => (
            <div key={group}>
              <div className="px-3 py-2 text-[10px] font-bold text-muted-foreground uppercase tracking-wider">{group}</div>
              {items.map((r) => (
                <div
                  key={r.id}
                  className="flex items-center gap-3 px-3 py-2 hover:bg-card hover:shadow-sm rounded-lg cursor-pointer text-sm text-foreground transition-all border border-transparent hover:border-border group"
                  onClick={() => { navigate(r.href); onClose(); setQuery(""); }}
                >
                  <r.icon className={`w-4 h-4 ${r.color} shrink-0`} />
                  <span className="truncate font-medium">{r.label}</span>
                  <span className="text-[10px] text-muted-foreground truncate">{r.hint}</span>
                  <ArrowRight className="w-3 h-3 ml-auto text-muted-foreground/60 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" />
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
