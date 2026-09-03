'use client';

import { useRouter } from "next/navigation";
import { Search, FileText, Bot } from "lucide-react";

interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
}

export function CommandPalette({ isOpen, onClose }: CommandPaletteProps) {
  const router = useRouter();

  if (!isOpen) return null;

  const suggestions = [
    { icon: FileText, iconClass: "text-blue-500 dark:text-blue-400", label: "总结 2024_Q2_产品规划.pdf", href: "/chat" },
    { icon: Bot, iconClass: "text-purple-500 dark:text-purple-400", label: "和 数据分析专家 对话", href: "/agents" },
  ];

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
            placeholder="搜索文件、智能体、或向 AI 提问..."
            className="w-full bg-transparent border-none focus:outline-none p-4 text-sm"
          />
          <kbd className="hidden sm:inline-block border border-gray-200 dark:border-gray-800 rounded px-1.5 py-0.5 text-[10px] text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-gray-900 shrink-0">ESC</kbd>
        </div>
        <div className="p-2 bg-gray-50/50 dark:bg-gray-900/50">
          <div className="px-3 py-2 text-[10px] font-bold text-gray-400 dark:text-gray-500 uppercase tracking-wider">建议搜索</div>
          {suggestions.map(({ icon: Icon, iconClass, label, href }) => (
            <div
              key={label}
              className="flex items-center gap-3 px-3 py-2 hover:bg-white dark:hover:bg-gray-800 hover:shadow-sm rounded-lg cursor-pointer text-sm text-gray-700 dark:text-gray-200 transition-all border border-transparent hover:border-gray-200 dark:hover:border-gray-800"
              onClick={() => { router.push(href); onClose(); }}
            >
              <Icon className={`w-4 h-4 ${iconClass} shrink-0`} />
              <span className="truncate">{label}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
