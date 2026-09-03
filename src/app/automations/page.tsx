'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Zap } from "lucide-react";
import { AutomationList } from "@/components/automations/AutomationList";
import { ExecutionHistory } from "@/components/automations/ExecutionHistory";

const TABS = ["规则", "执行历史"] as const;

export default function AutomationsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("规则");

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "自动任务", isCurrent: true }]}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <Zap className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> 自动任务
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              设置触发条件和执行动作，让 AI 自动处理重复性工作。
            </p>
            <div role="tablist" aria-label="自动任务视图" className="flex gap-1 p-1 bg-gray-200/50 dark:bg-gray-800/50 rounded-lg w-fit">
              {TABS.map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  aria-selected={tab === t}
                  onClick={() => setTab(t)}
                  className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${tab === t ? "bg-white dark:bg-gray-900 text-gray-800 dark:text-gray-100 shadow-sm" : "text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200"}`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            {tab === "规则" && <AutomationList />}
            {tab === "执行历史" && <ExecutionHistory />}
          </div>
        </div>
      </div>
    </>
  );
}
