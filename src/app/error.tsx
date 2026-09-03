"use client";

import { AlertTriangle, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function GlobalRouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="flex-1 flex items-center justify-center p-8">
      <div className="max-w-md text-center">
        <div className="w-16 h-16 bg-red-50 border border-red-100 rounded-2xl mx-auto flex items-center justify-center mb-4">
          <AlertTriangle className="w-8 h-8 text-red-400" />
        </div>
        <h2 className="text-lg font-bold text-gray-800 mb-2">页面出现了问题</h2>
        <p className="text-sm text-gray-500 mb-1">渲染时发生错误，可以尝试重新加载此页面。</p>
        {error.digest && <p className="text-[10px] text-gray-400 font-mono mb-4">错误码: {error.digest}</p>}
        <Button size="sm" className="h-8 text-xs bg-blue-600 hover:bg-blue-700" onClick={reset}>
          <RefreshCw className="w-3.5 h-3.5 mr-1.5" /> 重试
        </Button>
      </div>
    </div>
  );
}
