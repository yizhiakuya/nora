import { CloudOff, RefreshCw, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { useBackendHealth } from "@/hooks/useBackendHealth";

/**
 * 全局断连横幅(Gmail/Slack/Home Assistant 式文案,非模态细条):
 * - 状态词 + 下一步动作,不出现 server/backend/端口等内部术语
 * - 强调数据安全(数据截至 N 前,恢复后自动消失),不让用户慌
 * - 指数退避自动重试持续进行,无需用户操作;「立即重连」给急性子
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
        连接已断开,正在重连
      </span>
      <span className="text-[11px] text-amber-700/80 dark:text-amber-300/70 truncate">
        {ago
          ? `数据截至 ${ago}。服务恢复后这里会自动消失。`
          : "数据可能不是最新,服务恢复后这里会自动消失。"}
      </span>
      <span className="flex-1" />
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
        立即重连
      </Button>
    </div>
  );
}
