'use client';

import { useState, useMemo } from "react";
import { useRouter } from "next/navigation";
import { Search, Home, Folder, MessageSquare, BookOpen, Zap, Database, Server, ListCheck, Settings, File, FileCode, FileCog, Cpu, ArrowRight } from "lucide-react";
import { MOCK_FILES } from "@/lib/mockData";
import { MOCK_CONNECTIONS, MOCK_SERVICES } from "@/lib/devData";
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
  { id: "nav-home",    group: "页面", label: "首页",       hint: "概览与快捷入口", href: "/",              icon: Home,      color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-files",   group: "页面", label: "文件",       hint: "文件管理与预览", href: "/files",          icon: Folder,    color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-chat",    group: "页面", label: "对话",       hint: "AI 对话助手",    href: "/chat",           icon: MessageSquare, color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-know",    group: "页面", label: "知识库",     hint: "RAG 管线管理",   href: "/knowledge",      icon: BookOpen,  color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-skills",  group: "页面", label: "AI 能力",    hint: "定义 AI 能做什么", href: "/skills",       icon: Zap,       color: "text-yellow-500 dark:text-yellow-400" },
  { id: "nav-models",  group: "页面", label: "模型管理",   hint: "LLM 服务商接入",  href: "/models",       icon: Cpu,       color: "text-blue-500 dark:text-blue-400" },
  { id: "nav-ds",      group: "页面", label: "数据源",     hint: "数据库连接与查询", href: "/data-sources", icon: Database,  color: "text-purple-500 dark:text-purple-400" },
  { id: "nav-env",     group: "页面", label: "环境控制台", hint: "服务与日志",     href: "/environments",   icon: Server,    color: "text-green-500 dark:text-green-400" },
  { id: "nav-envvars", group: "页面", label: "环境变量",   hint: ".env 配置",      href: "/env-vars",      icon: FileCog,   color: "text-green-500 dark:text-green-400" },
  { id: "nav-auto",    group: "页面", label: "自动任务",   hint: "触发与执行历史",  href: "/automations",   icon: ListCheck, color: "text-yellow-500 dark:text-yellow-400" },
  { id: "nav-set",     group: "页面", label: "设置",       hint: "偏好与模型配置",  href: "/settings",      icon: Settings,  color: "text-gray-500 dark:text-gray-400" },
];

export function CommandPalette({ isOpen, onClose }: CommandPaletteProps) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const docs = useKnowledgeDocs((s) => s.docs);
  const automations = useAutomations((s) => s.rules);

  const index = useMemo<SearchResult[]>(() => [
    ...NAV_PAGES,
    ...MOCK_FILES.map((f) => ({
      id: `file-${f.id}`, group: "文件", label: f.name, hint: `${f.type} · ${f.size}`, href: "/files", icon: File, color: f.color,
    })),
    ...docs.slice(0, 20).map((d) => ({
      id: `doc-${d.id}`, group: "知识库", label: d.name, hint: `${d.chunks} chunks · ${d.source}`, href: "/knowledge", icon: d.source === "repo" ? FileCode : BookOpen, color: "text-blue-500 dark:text-blue-400",
    })),
    ...MOCK_CONNECTIONS.map((c) => ({
      id: `conn-${c.id}`, group: "数据源", label: c.name, hint: `${c.engine} · ${c.host}:${c.port || "—"}`, href: "/data-sources", icon: Database, color: "text-purple-500 dark:text-purple-400",
    })),
    ...MOCK_SERVICES.map((s) => ({
      id: `svc-${s.id}`, group: "服务", label: s.name, hint: `:${s.port} · ${s.status}`, href: "/environments", icon: Server, color: "text-green-500 dark:text-green-400",
    })),
    ...automations.map((a) => ({
      id: `auto-${a.id}`, group: "自动任务", label: a.name, hint: a.trigger, href: "/automations", icon: Zap, color: "text-yellow-500 dark:text-yellow-400",
    })),
  ], [docs, automations]);

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
        className="bg-white dark:bg-gray-900 rounded-xl shadow-2xl w-full max-w-[600px] overflow-hidden animate-in slide-in-from-top-4 duration-200 border border-gray-200 dark:border-gray-800"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center px-4 border-b border-gray-100 dark:border-gray-800">
          <Search className="w-5 h-5 text-gray-400 dark:text-gray-500 shrink-0" />
          <input
            autoFocus
            type="text"
            placeholder="搜索文件、知识库、数据源、服务、任务…"
            className="w-full bg-transparent border-none focus:outline-none p-4 text-sm text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <kbd className="hidden sm:inline-block border border-gray-200 dark:border-gray-800 rounded px-1.5 py-0.5 text-[10px] text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-gray-900 shrink-0">ESC</kbd>
        </div>
        <div className="p-2 bg-gray-50/50 dark:bg-gray-900/50 max-h-[400px] overflow-y-auto custom-scroll">
          {grouped.length === 0 && (
            <div className="py-10 text-center text-xs text-gray-400 dark:text-gray-500">
              没有匹配「{query}」的结果
            </div>
          )}
          {grouped.map(([group, items]: [string, SearchResult[]]) => (
            <div key={group}>
              <div className="px-3 py-2 text-[10px] font-bold text-gray-400 dark:text-gray-500 uppercase tracking-wider">{group}</div>
              {items.map((r) => (
                <div
                  key={r.id}
                  className="flex items-center gap-3 px-3 py-2 hover:bg-white dark:hover:bg-gray-800 hover:shadow-sm rounded-lg cursor-pointer text-sm text-gray-700 dark:text-gray-200 transition-all border border-transparent hover:border-gray-200 dark:hover:border-gray-800 group"
                  onClick={() => { router.push(r.href); onClose(); setQuery(""); }}
                >
                  <r.icon className={`w-4 h-4 ${r.color} shrink-0`} />
                  <span className="truncate font-medium">{r.label}</span>
                  <span className="text-[10px] text-gray-400 dark:text-gray-500 truncate">{r.hint}</span>
                  <ArrowRight className="w-3 h-3 ml-auto text-gray-300 dark:text-gray-600 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" />
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
