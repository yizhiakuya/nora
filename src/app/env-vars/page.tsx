'use client';

import { Header } from "@/components/layout/Header";
import { FileCog } from "lucide-react";
import { EnvEditor } from "@/components/environments/EnvEditor";

export default function EnvVarsPage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "环境变量", isCurrent: true }]}
        actions={<span className="text-xs text-gray-400 dark:text-gray-500 hidden sm:inline font-mono">.env.development</span>}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-4xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <FileCog className="w-5 h-5 text-green-600 dark:text-green-400" /> 环境变量
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              本地开发服务的运行配置。修改后需重启相关服务（到「环境控制台」操作）才会生效。
            </p>
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            <EnvEditor />
          </div>
        </div>
      </div>
    </>
  );
}
