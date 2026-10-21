'use client';

import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";

interface KnowledgeTabsProps {
  active: string;
  onChange: (tab: string) => void;
}

const TABS = ["文档库", "检索测试", "索引状态"];

export function KnowledgeTabs({ active, onChange }: KnowledgeTabsProps) {
  return (
    <Tabs value={active} onValueChange={onChange}>
    <TabsList aria-label="知识库视图" className="h-9 w-fit gap-1 border-0 rounded-lg bg-muted/60 p-1">
      {TABS.map((tab) => (
        <TabsTrigger key={tab} value={tab} className="border-0 rounded-md px-3 py-1 text-xs data-[state=active]:bg-card data-[state=active]:shadow-surface">
          {tab}
        </TabsTrigger>
      ))}
    </TabsList>
    </Tabs>
  );
}
