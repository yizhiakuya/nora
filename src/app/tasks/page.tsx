'use client';

import { useState, useEffect, useMemo } from "react";
import { Header } from "@/components/layout/Header";
import { ListCheck, Search, PlayCircle, Filter } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TaskTable } from "@/components/tasks/TaskTable";
import { MockAPI } from "@/lib/api/mockApi";
import { useGlobalTasks } from "@/hooks/useGlobalTasks";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";

const TAB_STATUS: Record<string, string | null> = {
  "全部任务": null,
  "运行中": "running",
  "已排期": "scheduled",
  "已完成": "completed",
  "失败": "failed",
};

export default function TasksPage() {
  const [activeTab, setActiveTab] = useState("全部任务");
  const [searchQuery, setSearchQuery] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const tasks = useGlobalTasks((s) => s.tasks);
  const setTasks = useGlobalTasks((s) => s.setTasks);
  const debouncedQuery = useDebouncedValue(searchQuery, 300);

  // 从唯一数据源拉取任务，写入全局 store（Header 角标同步消费）
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setIsLoading(true);
      setError(null);
      try {
        const data = await MockAPI.tasks.getList();
        if (!cancelled) setTasks(data);
      } catch {
        if (!cancelled) setError("网络异常，无法获取任务列表。");
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [setTasks, reloadKey]);

  const filteredTasks = useMemo(() => {
    const status = TAB_STATUS[activeTab];
    const q = debouncedQuery.trim().toLowerCase();
    return tasks.filter((t) => {
      if (status && t.status !== status) return false;
      if (q && !t.name.toLowerCase().includes(q) && !t.agent.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [tasks, activeTab, debouncedQuery]);

  const tabs = Object.keys(TAB_STATUS);

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "AI 工作台", isCurrent: false },
          { label: "任务中心", isCurrent: true },
        ]}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white text-gray-700">
              <Filter className="w-3.5 h-3.5 mr-1.5" /> 过滤条件
            </Button>
            <Button size="sm" className="h-8 text-xs bg-blue-600 hover:bg-blue-700">
              <PlayCircle className="w-3.5 h-3.5 mr-1.5" /> 新建定时任务
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-[#f4f5f7]">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="flex flex-col mb-6 space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-gray-800 flex items-center gap-2">
                <ListCheck className="w-5 h-5 text-blue-500" /> 任务中心 (Tasks)
              </h1>
              <p className="text-xs text-gray-500 mt-1">监控 AI 智能体的后台执行任务、批量处理任务与定时调度任务。</p>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex gap-1 p-1 bg-gray-200/50 rounded-lg">
                {tabs.map((tab) => (
                  <div
                    key={tab}
                    onClick={() => setActiveTab(tab)}
                    className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${
                      activeTab === tab ? "bg-white text-gray-800 shadow-sm" : "text-gray-500 hover:text-gray-700 hover:bg-gray-200/50"
                    }`}
                  >
                    {tab}
                  </div>
                ))}
              </div>

              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 w-3 h-3" />
                <Input
                  placeholder="搜索任务名称或智能体..."
                  className="pl-7 pr-3 py-1.5 h-8 bg-white border-gray-200 text-xs shadow-sm w-64 focus-visible:ring-1 focus-visible:ring-blue-500"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>
            </div>
          </div>

          <TaskTable tasks={filteredTasks} isLoading={isLoading} error={error} onRetry={() => setReloadKey((k) => k + 1)} />
        </div>
      </div>
    </>
  );
}
