'use client';

import { Search, Filter, FileDown, FileOutput, FileWarning, Activity } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SOURCES = [
  { name: "2023年年度财报_Final.pdf", icon: FileDown, iconClass: "text-red-500 dark:text-red-400", type: "本地文件", status: "已激活", badge: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-400 border-green-100 dark:border-green-900", syncing: false },
  { name: "产品研发 Wiki 库", icon: FileOutput, iconClass: "text-blue-500 dark:text-blue-400", type: "OAuth 同步", status: "向量化中", badge: "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400 border-blue-100 dark:border-blue-900", syncing: true },
  { name: "2021历史账单.xlsx", icon: FileWarning, iconClass: "text-gray-500 dark:text-gray-400", type: "本地文件", status: "解析异常", badge: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-400 border-red-100 dark:border-red-900", syncing: false },
];

export function DataSourceTable() {
  return (
    <div className="w-full lg:w-[65%] flex flex-col space-y-4">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100">数据源明细</h2>
        <div className="flex gap-2">
          <div className="relative flex-1 sm:flex-none">
            <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 dark:text-gray-500 w-3 h-3" />
            <Input placeholder="搜索文件或链接..." className="pl-7 pr-3 py-1.5 h-8 bg-white dark:bg-gray-900 border-gray-200 dark:border-gray-800 text-xs shadow-sm w-full sm:w-48 focus-visible:ring-1 focus-visible:ring-blue-500 transition-all focus:w-full sm:focus:w-64" />
          </div>
          <Button variant="outline" size="icon" className="h-8 w-8 text-gray-600 dark:text-gray-300 shadow-sm hover:bg-gray-50 dark:hover:bg-gray-800 shrink-0"><Filter className="w-3.5 h-3.5" /></Button>
        </div>
      </div>

      <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl shadow-sm overflow-hidden overflow-x-auto">
        <table className="w-full min-w-[500px] text-left border-collapse">
          <thead>
            <tr className="bg-gray-50 dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 text-xs text-gray-500 dark:text-gray-400 tracking-wider">
              <th className="p-3 pl-4 font-medium">资源名称</th>
              <th className="p-3 font-medium">类型</th>
              <th className="p-3 font-medium whitespace-nowrap">状态</th>
            </tr>
          </thead>
          <tbody className="text-sm">
            {SOURCES.map(({ name, icon: Icon, iconClass, type, status, badge, syncing }) => (
              <tr key={name} className={`border-b border-gray-100 dark:border-gray-800 ${syncing ? "bg-blue-50/10 dark:bg-blue-950/10 hover:bg-blue-50/20 dark:hover:bg-blue-950/20" : "hover:bg-gray-50 dark:hover:bg-gray-800"} transition-colors cursor-pointer`}>
                <td className="p-3 pl-4">
                  <div className="flex items-center gap-3">
                    <Icon className={`${iconClass} w-4 h-4 shrink-0`} />
                    <div className="min-w-0"><div className="font-medium text-gray-800 dark:text-gray-100 truncate">{name}</div></div>
                  </div>
                </td>
                <td className="p-3 text-gray-500 dark:text-gray-400 text-xs whitespace-nowrap">{type}</td>
                <td className="p-3 whitespace-nowrap">
                  <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs ${badge} border`}>
                    {syncing && <Activity className="w-3 h-3 animate-pulse" />} {status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
