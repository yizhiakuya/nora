'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { BookOpen, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { KnowledgeTabs } from "@/components/knowledge/KnowledgeTabs";
import { DocumentLibrary } from "@/components/knowledge/DocumentLibrary";
import { RetrievalTest } from "@/components/knowledge/RetrievalTest";
import { IndexStatus } from "@/components/knowledge/IndexStatus";
import { DataGraphView } from "@/components/knowledge/DataGraphView";
import { CleaningRules } from "@/components/knowledge/CleaningRules";

export default function KnowledgePage() {
  const [activeTab, setActiveTab] = useState("文档库");

  return (
    <>
      <Header
        breadcrumbs={[{ label: "AI 工作台", isCurrent: false }, { label: "知识库", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600">
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 导入文档
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
                <BookOpen className="w-5 h-5 text-blue-600 dark:text-blue-400" /> 知识库 (Context Pipeline)
              </h1>
              <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                文件、数据库、代码、环境配置统一摄入 → 清洗 → 索引，为 AI 提供准确上下文。
              </p>
            </div>
            <KnowledgeTabs active={activeTab} onChange={setActiveTab} />
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            {activeTab === "文档库" && <DocumentLibrary />}
            {activeTab === "检索测试" && <RetrievalTest />}
            {activeTab === "索引状态" && <IndexStatus />}
            {activeTab === "数据图谱" && <DataGraphView />}
            {activeTab === "清洗规则" && <CleaningRules />}
          </div>
        </div>
      </div>
    </>
  );
}
