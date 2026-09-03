'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Database, Search, Plus, Filter } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SourceCard } from "@/components/data-sources/SourceCard";
import { MOCK_SOURCES } from "@/lib/mockData";

export default function DataSourcesPage() {
  const [searchQuery, setSearchQuery] = useState("");

  const filteredSources = MOCK_SOURCES.filter(
    (source) =>
      source.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      source.type.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "AI 工作台", isCurrent: false },
          { label: "数据源", isCurrent: true },
        ]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600">
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 接入新数据源
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="flex flex-col mb-6 space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
                <Database className="w-5 h-5 text-purple-600 dark:text-purple-400" /> 数据源管理 (Data Sources)
              </h1>
              <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">集中管理 AI 能够访问的外部数据库、API 接口和本地存储资源，为知识库和智能体提供数据流。</p>
            </div>

            <div className="flex items-center justify-between">
              <Button variant="outline" size="sm" className="h-8 text-xs bg-white dark:bg-gray-900 text-gray-700 dark:text-gray-200">
                <Filter className="w-3.5 h-3.5 mr-1.5" /> 筛选状态
              </Button>

              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 dark:text-gray-500 w-3 h-3" />
                <Input
                  placeholder="搜索数据源名称或类型..."
                  className="pl-7 pr-3 py-1.5 h-8 bg-white dark:bg-gray-900 border-gray-200 dark:border-gray-800 text-xs shadow-sm w-64 focus-visible:ring-1 focus-visible:ring-blue-500"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {filteredSources.map((source) => (
              <SourceCard key={source.id} source={source} />
            ))}
          </div>

          {filteredSources.length === 0 && (
            <div className="py-20 flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 animate-in fade-in">
              <Database className="w-12 h-12 mb-4 opacity-20" />
              <div className="text-sm font-medium">没有任何接入的数据源</div>
              <div className="text-xs mt-1">点击右上角按钮接入你的第一个数据源</div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
