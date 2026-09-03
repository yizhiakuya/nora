'use client';

import { Header } from "@/components/layout/Header";
import { Server } from "lucide-react";
import { ServiceCards } from "@/components/environments/ServiceCards";
import { LogStream } from "@/components/environments/LogStream";

export default function EnvironmentsPage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "AI 工作台", isCurrent: false }, { label: "环境控制台", isCurrent: true }]}
        actions={<span className="text-xs text-gray-400 dark:text-gray-500 hidden sm:inline">本地开发环境</span>}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <Server className="w-5 h-5 text-green-600 dark:text-green-400" /> 环境控制台
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              管理本地/测试环境服务，查看日志流，AI 自动诊断错误并给出修复建议。
            </p>
          </div>

          <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <ServiceCards />
            <LogStream />
          </div>
        </div>
      </div>
    </>
  );
}
