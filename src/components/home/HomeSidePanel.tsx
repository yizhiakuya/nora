'use client';

import { useRouter } from "next/navigation";
import { Bot, Database } from "lucide-react";

const AGENTS = [
  { name: "数据分析专家", desc: "关联 3 个知识库资源", avatar: "数", avatarClass: "bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400" },
  { name: "产品文档助理", desc: "关联 12 个知识库资源", avatar: "产", avatarClass: "bg-purple-100 dark:bg-purple-900/50 text-purple-600 dark:text-purple-400" },
];

export function HomeSidePanel() {
  const router = useRouter();

  return (
    <div className="w-full lg:w-[35%] flex flex-col space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
          <Bot className="w-4 h-4 text-orange-500 dark:text-orange-400" /> 常用智能体
        </h2>
      </div>
      <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl shadow-sm p-2 flex flex-col gap-2">
        {AGENTS.map(({ name, desc, avatar, avatarClass }) => (
          <div
            key={name}
            onClick={() => router.push("/agents")}
            className="flex items-center gap-3 p-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer transition-colors border border-transparent hover:border-gray-200 dark:hover:border-gray-800"
          >
            <div className={`w-8 h-8 ${avatarClass} rounded-lg flex items-center justify-center font-bold text-xs shadow-inner shrink-0`}>{avatar}</div>
            <div>
              <div className="text-sm font-medium text-gray-800 dark:text-gray-100">{name}</div>
              <div className="text-[10px] text-gray-500 dark:text-gray-400">{desc}</div>
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center justify-between mt-2">
        <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
          <Database className="w-4 h-4 text-purple-500 dark:text-purple-400" /> 存储空间
        </h2>
      </div>
      <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl p-5 shadow-sm">
        <div className="flex justify-between items-end mb-3">
          <div>
            <div className="text-2xl font-bold text-gray-800 dark:text-gray-100">12.5 <span className="text-sm text-gray-500 dark:text-gray-400 font-normal">GB</span></div>
            <div className="text-[10px] text-gray-400 dark:text-gray-500 mt-1">总计 50 GB</div>
          </div>
          <div className="text-xs font-medium text-blue-600 dark:text-blue-400">25% 已用</div>
        </div>
        <div className="w-full h-2 bg-gray-100 dark:bg-gray-800 rounded-full overflow-hidden flex mb-4">
          <div className="h-full bg-blue-500" style={{ width: "25%" }}></div>
        </div>
      </div>
    </div>
  );
}
