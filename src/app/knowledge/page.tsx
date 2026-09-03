'use client';

import { Header } from "@/components/layout/Header";
import { Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MetricsCards } from "@/components/knowledge/MetricsCards";
import { DataSourceTable } from "@/components/knowledge/DataSourceTable";
import { RetrievalTest } from "@/components/knowledge/RetrievalTest";
import { KnowledgeImportModal } from "@/components/knowledge/ImportModal";

export default function KnowledgePage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "AI 工作台", isCurrent: false }, { label: "知识库", isCurrent: false }, { label: "核心产品语料库", isCurrent: true }]}
        actions={
          <div className="flex items-center gap-1 sm:gap-2 shrink-0">
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white text-gray-700 hidden sm:flex shrink-0">
              <Settings className="w-3.5 h-3.5 mr-1.5" /> 设置
            </Button>
            <KnowledgeImportModal />
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 sm:p-6 custom-scroll relative bg-[#f4f5f7]">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <MetricsCards />

          <div className="flex flex-col lg:flex-row gap-6">
            <DataSourceTable />
            <RetrievalTest />
          </div>
        </div>
      </div>
    </>
  );
}
