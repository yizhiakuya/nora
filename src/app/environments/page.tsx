'use client';

import { useState, useEffect } from "react";
import { Header } from "@/components/layout/Header";
import { Server } from "lucide-react";
import { ServiceCards } from "@/components/environments/ServiceCards";
import { LogStream } from "@/components/environments/LogStream";
import { EnvEditor } from "@/components/environments/EnvEditor";

const TABS = ["服务", "日志", "环境变量"] as const;

export default function EnvironmentsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("服务");

  // 支持 /environments?tab=环境变量 深链
  useEffect(() => {
    const t = new URLSearchParams(window.location.search).get("tab");
    if (t === "环境变量") setTab("环境变量");
  }, []);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "环境控制台", isCurrent: true }]}
        actions={<span className="text-xs text-gray-400 dark:text-gray-500 hidden sm:inline">本地开发环境</span>}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <Server className="w-5 h-5 text-green-600 dark:text-green-400" /> 环境控制台
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              管理本地/测试环境服务，查看日志流，AI 自动诊断错误并给出修复建议。
            </p>
            <div role="tablist" aria-label="环境视图" className="flex gap-1 p-1 bg-gray-200/50 dark:bg-gray-800/50 rounded-lg w-fit">
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

          <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {tab === "服务" && (
              <>
                <ServiceCards />
                <LogStream />
              </>
            )}
            {tab === "日志" && <LogStream />}
            {tab === "环境变量" && <EnvEditor />}
          </div>
        </div>
      </div>
    </>
  );
}
