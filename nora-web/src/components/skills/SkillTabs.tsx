'use client';

import { Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { TabsList, TabsTrigger } from "@/components/ui/tabs";

const CATEGORIES = ["全部", "内置", "数据", "计算", "环境", "通知", "自定义"];

interface SkillTabsProps {
  searchQuery: string;
  onSearchChange: (query: string) => void;
}

export function SkillTabs({ searchQuery, onSearchChange }: SkillTabsProps) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <TabsList aria-label="技能分类" className="h-auto flex-wrap">
        {CATEGORIES.map((category) => <TabsTrigger key={category} value={category}>{category}</TabsTrigger>)}
      </TabsList>
      <div className="relative w-full sm:w-56">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground w-4 h-4" />
        <Input aria-label="查找技能" placeholder="搜索名称或描述…" className="pl-9" value={searchQuery} onChange={(e) => onSearchChange(e.target.value)} />
      </div>
    </div>
  );
}
