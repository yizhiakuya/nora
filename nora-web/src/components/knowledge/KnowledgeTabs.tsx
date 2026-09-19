'use client';

interface KnowledgeTabsProps {
  active: string;
  onChange: (tab: string) => void;
}

const TABS = ["文档库", "检索测试", "索引状态"];

export function KnowledgeTabs({ active, onChange }: KnowledgeTabsProps) {
  return (
    <div role="tablist" aria-label="知识库视图" className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit">
      {TABS.map((tab) => (
        <button
          key={tab}
          type="button"
          role="tab"
          aria-selected={active === tab}
          onClick={() => onChange(tab)}
          className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${active === tab ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground hover:bg-gray-200/50 dark:hover:bg-gray-700/50"}`}
        >
          {tab}
        </button>
      ))}
    </div>
  );
}
