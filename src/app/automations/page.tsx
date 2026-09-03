'use client';

import { Header } from "@/components/layout/Header";
import { Zap } from "lucide-react";
import { AutomationList } from "@/components/automations/AutomationList";

export default function AutomationsPage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "自动任务", isCurrent: true }]}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <Zap className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> 自动任务
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              设置触发条件和执行动作，让 AI 自动处理重复性工作。
            </p>
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            <AutomationList />
          </div>
        </div>
      </div>
    </>
  );
}
