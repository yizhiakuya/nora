'use client';

import { Search } from "lucide-react";
import { Input } from "@/components/ui/input";

const TABS = ["全部", "计算", "数据", "搜索", "集成", "自定义"];

interface SkillTabsProps {
  activeTab: string;
  onTabChange: (tab: string) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
}

export function SkillTabs({ activeTab, onTabChange, searchQuery, onSearchChange }: SkillTabsProps) {
  return (
    <div className="flex items-center justify-between">
      <div role="tablist" aria-label="技能分类" className="flex gap-1 p-1 bg-gray-200/50 dark:bg-gray-800/50 rounded-lg">
        {TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            onClick={() => onTabChange(tab)}
            className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${activeTab === tab ? "bg-white dark:bg-gray-900 text-gray-800 dark:text-gray-100 shadow-sm" : "text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-200/50 dark:hover:bg-gray-700/50"}`}
          >
            {tab}
          </button>
        ))}
      </div>

      <div className="relative">
        <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 dark:text-gray-500 w-3 h-3" />
        <Input
          placeholder="搜索技能名称或描述..."
          className="pl-7 pr-3 py-1.5 h-8 bg-white dark:bg-gray-900 border-gray-200 dark:border-gray-800 text-xs shadow-sm w-64 focus-visible:ring-1 focus-visible:ring-blue-500"
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
        />
      </div>
    </div>
  );
}
