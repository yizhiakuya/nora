import { CloudOff, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useBackendHealth } from "@/hooks/useBackendHealth";

/**
 * 全局后端离线横幅：探测到后端不可达时固定在内容区顶部,
 * 提供手动重试;恢复在线自动消失。
 */
export function BackendOfflineBanner() {
  const online = useBackendHealth((s) => s.online);
  const check = useBackendHealth((s) => s.check);
  if (online !== false) return null;

  return (
    <div className="mx-4 sm:mx-8 mt-3 flex items-center gap-3 rounded-xl border border-amber-200 dark:border-amber-900/60 bg-amber-50 dark:bg-amber-950/30 px-4 py-2.5 animate-in fade-in slide-in-from-top-2">
      <CloudOff className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0" />
      <div className="flex-1 min-w-0">
        <p className="text-xs font-medium text-amber-800 dark:text-amber-200">
          后端服务连接不上——列表与操作暂不可用,数据不会丢失。
        </p>
        <p className="text-[10px] text-amber-700/80 dark:text-amber-300/80 mt-0.5">
          请确认 nora-api 服务已启动(网关 :8080),恢复后本提示自动消失。
        </p>
      </div>
      <Button
        variant="outline"
        size="sm"
        className="h-7 text-[11px] px-2.5 shrink-0 border-amber-300 dark:border-amber-800 hover:bg-amber-100 dark:hover:bg-amber-900/40"
        onClick={() => void check()}
      >
        <RefreshCw className="w-3 h-3 mr-1" /> 重试
      </Button>
    </div>
  );
}
