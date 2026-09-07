import { CloudOff, RefreshCw, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { useBackendHealth } from "@/hooks/useBackendHealth";

/**
 * 全局后端离线横幅(Gmail/Slack 式:非模态细条,不阻塞内容):
 * - 所有页面显示(主页数据面板同样来自后端,需要知情)
 * - 文案给出具体排查方向(Notion 帮助中心风格),不指责用户
 * - 30s 自动轮询持续进行,横幅右上角显示"将自动重试";恢复在线自动消失
 */
export function BackendOfflineBanner() {
  const online = useBackendHealth((s) => s.online);
  const check = useBackendHealth((s) => s.check);
  const lastCheckedAt = useBackendHealth((s) => s.lastCheckedAt);
  const [manualChecking, setManualChecking] = useState(false);
  const [ago, setAgo] = useState("");

  useEffect(() => {
    if (online !== false) return;
    const tick = () => {
      const s = Math.floor((Date.now() - (lastCheckedAt ?? Date.now())) / 1000);
      setAgo(s < 5 ? "刚刚" : s < 60 ? `${s} 秒前` : `${Math.floor(s / 60)} 分钟前`);
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => clearInterval(t);
  }, [online, lastCheckedAt]);

  if (online !== false) return null;

  return (
    <div
      role="status"
      className="mx-4 sm:mx-8 mt-2 flex items-center gap-2.5 rounded-lg border border-amber-200/80 dark:border-amber-900/50 bg-amber-50/80 dark:bg-amber-950/20 px-3 py-1.5 animate-in fade-in slide-in-from-top-1"
    >
      <CloudOff className="w-3.5 h-3.5 text-amber-600 dark:text-amber-400 shrink-0" />
      <span className="text-xs font-medium text-amber-800 dark:text-amber-200 shrink-0">
        无法连接后端服务
      </span>
      <span className="text-[11px] text-amber-700/80 dark:text-amber-300/70 truncate">
        数据可能不是最新。请确认 nora-api 已启动(网关 :8080)或检查网络/代理设置。
      </span>
      <span className="flex-1" />
      <span className="hidden sm:inline text-[10px] text-amber-700/60 dark:text-amber-300/50 tabular-nums shrink-0">
        检查于 {ago} · 将自动重试
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={manualChecking}
        className="h-6 text-[11px] px-2 shrink-0 border-amber-300/70 dark:border-amber-800/60 hover:bg-amber-100 dark:hover:bg-amber-900/40"
        onClick={() => {
          setManualChecking(true);
          void check().finally(() => setManualChecking(false));
        }}
      >
        {manualChecking ? (
          <Loader2 className="w-3 h-3 mr-1 animate-spin" />
        ) : (
          <RefreshCw className="w-3 h-3 mr-1" />
        )}
        重试
      </Button>
    </div>
  );
}
