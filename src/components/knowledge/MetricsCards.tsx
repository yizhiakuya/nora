'use client';

import { FileText, Database, Activity, Users } from "lucide-react";

const METRICS = [
  { label: "文档总数", value: "1,248", sub: "↑ 本周新增 42 份", subClass: "text-green-500", icon: FileText, iconClass: "bg-blue-50 text-blue-500" },
  { label: "已向量化字符", value: "42.5 M", sub: "分块策略: 自动最优 (500/chunk)", subClass: "text-gray-400", icon: Database, iconClass: "bg-purple-50 text-purple-500" },
  { label: "知识库健康度", value: "98%", sub: "", subClass: "", icon: Activity, iconClass: "bg-green-50 text-green-500", progress: true },
  { label: "关联智能体", value: "8", sub: "", subClass: "", icon: Users, iconClass: "bg-orange-50 text-orange-500" },
];

export function MetricsCards() {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
      {METRICS.map(({ label, value, sub, subClass, icon: Icon, iconClass, progress }) => (
        <div key={label} className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm relative overflow-hidden transition-all hover:shadow-md">
          <div className="flex justify-between items-start mb-2">
            <span className="text-xs font-medium text-gray-500">{label}</span>
            <div className={`w-6 h-6 rounded-md ${iconClass} flex items-center justify-center`}><Icon className="w-3 h-3" /></div>
          </div>
          <div className="text-2xl font-bold text-gray-800">{value}</div>
          {sub && <div className={`text-[10px] font-medium mt-1 ${subClass}`}>{sub}</div>}
          {progress && (
            <div className="w-full h-1 bg-gray-100 rounded-full overflow-hidden flex mt-2">
              <div className="h-full bg-green-500" style={{ width: "98%" }}></div>
              <div className="h-full bg-red-400" style={{ width: "2%" }}></div>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
