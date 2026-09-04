'use client';

import { useNavigate } from "react-router-dom";
import { FileText, FileSpreadsheet, Plus } from "lucide-react";

const ACTIONS = [
  {
    label: "总结这份文档",
    desc: "提取核心观点和行动项",
    icon: FileText,
    iconBg: "bg-blue-600 dark:bg-blue-500",
    gradient: "from-blue-50 dark:from-blue-950/30 to-indigo-50 dark:to-indigo-950/30",
    hoverBorder: "hover:border-blue-200 dark:hover:border-blue-800",
    href: "/chat",
  },
  {
    label: "巡检数据分析",
    desc: "基于巡检记录生成趋势图表",
    icon: FileSpreadsheet,
    iconBg: "bg-purple-600 dark:bg-purple-500",
    gradient: "from-purple-50 dark:from-purple-950/30 to-fuchsia-50 dark:to-fuchsia-950/30",
    hoverBorder: "hover:border-purple-200 dark:hover:border-purple-800",
    href: "/chat",
  },
];

export function QuickActions() {
  const navigate = useNavigate();

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4 animate-in fade-in slide-in-from-bottom-3 duration-500">
      {ACTIONS.map(({ label, desc, icon: Icon, iconBg, gradient, hoverBorder, href }) => (
        <div
          key={label}
          onClick={() => navigate(href)}
          className={`group border border-transparent bg-gradient-to-br ${gradient} p-4 rounded-xl cursor-pointer hover:shadow-md ${hoverBorder} transition-all relative overflow-hidden`}
        >
          <div className={`w-8 h-8 rounded-lg ${iconBg} text-white flex items-center justify-center mb-3 shadow-sm group-hover:scale-110 transition-transform`}>
            <Icon className="w-4 h-4" />
          </div>
          <div className="text-sm font-bold text-foreground mb-1">{label}</div>
          <div className="text-[10px] text-muted-foreground">{desc}</div>
        </div>
      ))}

      <div
        onClick={() => navigate("/knowledge")}
        className="group border border-dashed border-gray-300 dark:border-gray-700 bg-card p-4 rounded-xl cursor-pointer hover:border-blue-400 dark:hover:border-blue-600 hover:bg-blue-50/50 dark:hover:bg-blue-950/30 transition-all flex flex-col items-center justify-center text-muted-foreground hover:text-blue-500 dark:hover:text-blue-400 min-h-[110px]"
      >
        <Plus className="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
        <div className="text-xs font-medium">导入文档到知识库</div>
      </div>
    </div>
  );
}
