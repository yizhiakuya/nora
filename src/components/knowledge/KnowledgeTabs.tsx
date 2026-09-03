'use client';

interface KnowledgeTabsProps {
  active: string;
  onChange: (tab: string) => void;
}

const TABS = ["文档库", "检索测试", "索引状态", "数据图谱", "清洗规则"];

export function KnowledgeTabs({ active, onChange }: KnowledgeTabsProps) {
  return (
    <div role="tablist" aria-label="知识库视图" className="flex gap-1 p-1 bg-gray-200/50 dark:bg-gray-800/50 rounded-lg w-fit">
      {TABS.map((tab) => (
        <button
          key={tab}
          type="button"
          role="tab"
          aria-selected={active === tab}
          onClick={() => onChange(tab)}
          className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${active === tab ? "bg-white dark:bg-gray-900 text-gray-800 dark:text-gray-100 shadow-sm" : "text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-200/50 dark:hover:bg-gray-700/50"}`}
        >
          {tab}
        </button>
      ))}
    </div>
  );
}
