'use client';

import { Header } from "@/components/layout/Header";
import { BookOpen } from "lucide-react";
import { KnowledgeView } from "@/components/knowledge/KnowledgeView";

/**
 * /knowledge 兼容页(M1-03,2026-09-20):资料页的长期知识视图现位于
 * /files?view=knowledge;此路由保留直达(书签/深链不丢目标),内容与
 * 资料页的知识视图完全一致。
 */
export default function KnowledgePage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "资料", href: "/files", isCurrent: false }, { label: "长期知识", isCurrent: true }]}
      />
      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
                <BookOpen className="w-5 h-5 text-blue-600 dark:text-blue-400" /> 长期知识
              </h1>
            </div>
          </div>
          <KnowledgeView />
        </div>
      </div>
    </>
  );
}
