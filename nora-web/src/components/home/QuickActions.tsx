'use client';

import { lazy, Suspense, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Globe, FileText, FileSpreadsheet, BookOpen, ArrowUpRight } from "lucide-react";

const WebTaskDialog = lazy(() => import("@/components/browser/WebTaskDialog"));

const ACTIONS = [
  { label: "网页资料收集", desc: "先确认样本，再整理成表格", icon: Globe, href: null },
  { label: "总结文档", desc: "提取核心观点和行动项", icon: FileText,
    href: "/chat?prompt=" + encodeURIComponent("请帮我总结一份文档:先列出资料里可选的文档,我选好后再读取内容并提取核心观点和行动项。") },
  { label: "巡检数据分析", desc: "查看趋势，发现数据中的问题", icon: FileSpreadsheet,
    href: "/chat?prompt=" + encodeURIComponent("帮我做一次巡检数据分析:先看数据源里有哪些库表,列出可分析的巡检/日志数据,我选好后查询并生成趋势结论。") },
  { label: "管理长期知识", desc: "导入资料，建立常用知识", icon: BookOpen, href: "/knowledge" },
];

export function QuickActions() {
  const navigate = useNavigate();
  const [taskOpen, setTaskOpen] = useState(false);

  return (
    <>
      <div className="grid grid-cols-1 min-[360px]:grid-cols-2 lg:grid-cols-4 gap-3">
        {ACTIONS.map(({ label, desc, icon: Icon, href }) => (
          <button type="button" key={label} onClick={() => href ? navigate(href) : setTaskOpen(true)}
            className="group surface flex flex-col items-start text-left p-4 hover:border-primary/30 hover:bg-accent/30 transition-colors">
            <div className="flex w-full items-center justify-between mb-3">
              <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-muted text-muted-foreground group-hover:text-primary transition-colors"><Icon className="h-4 w-4" /></span>
              <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground/60 group-hover:text-primary transition-colors" />
            </div>
            <div className="text-[13px] font-semibold text-foreground">{label}</div>
            <div className="mt-1.5 text-xs leading-relaxed text-muted-foreground">{desc}</div>
          </button>
        ))}
      </div>
      {taskOpen && <Suspense fallback={null}><WebTaskDialog onClose={() => setTaskOpen(false)} /></Suspense>}
    </>
  );
}
