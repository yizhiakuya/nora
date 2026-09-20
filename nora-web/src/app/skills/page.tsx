'use client';

import { Header } from "@/components/layout/Header";
import { Zap } from "lucide-react";
import { SkillsView } from "@/components/skills/SkillsView";

/**
 * /skills 兼容页(M1-04,2026-09-20):技能管理现位于
 * /settings?section=skills;此路由保留直达(书签/深链不丢目标)。
 */
export default function SkillsPage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "设置", href: "/settings", isCurrent: false }, { label: "技能", isCurrent: true }]}
      />
      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Zap className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> 技能
            </h1>
          </div>
          <SkillsView />
        </div>
      </div>
    </>
  );
}
