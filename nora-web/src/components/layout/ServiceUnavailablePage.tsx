import { useEffect, useState } from "react";
import { useBackendHealth } from "@/hooks/useBackendHealth";

/**
 * 全屏「服务不可用」页:API 不在线时替换整个应用(无侧栏/导航/按钮)。
 * 点阵网格背景 + 居中大号三角警告,极简一屏:只有状态,别无他物。
 * 自动重连:指数退避探测(5s→10s→20s→30s)后台持续进行,服务恢复后
 * 本页自动消失、回到用户原所在页面。
 */
export function ServiceUnavailablePage() {
  const lastCheckedAt = useBackendHealth((s) => s.lastCheckedAt);
  // 呼吸动画节奏与退避探测同步感知:每次探测后闪烁一次
  const [pulse, setPulse] = useState(false);
  const [ago, setAgo] = useState("刚刚");

  useEffect(() => {
    const tick = () => {
      const s = Math.floor((Date.now() - (lastCheckedAt ?? Date.now())) / 1000);
      setAgo(s < 5 ? "刚刚" : s < 60 ? `${s} 秒前` : `${Math.floor(s / 60)} 分钟前`);
      setPulse(true);
      const t = setTimeout(() => setPulse(false), 600);
      return () => clearTimeout(t);
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => clearInterval(t);
  }, [lastCheckedAt]);

  return (
    <div
      className="h-screen flex flex-col items-center justify-center bg-background text-gray-800 dark:text-gray-100 select-none"
      style={{
        // hsl(var(--dot-grid)) 取主题里的点阵颜色(亮/暗各一份)
        backgroundImage:
          "radial-gradient(circle, hsl(var(--dot-grid) / 0.35) 1px, transparent 1px)",
        backgroundSize: "24px 24px",
      }}
    >
      {/* 大号三角警告:描边风格,点阵之上居中 */}
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`w-40 h-40 text-amber-500 dark:text-amber-400 transition-opacity duration-500 ${pulse ? "opacity-60" : "opacity-100"}`}
        aria-hidden
      >
        <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>

      <h1 className="mt-8 text-2xl font-semibold tracking-wide text-foreground">
        服务不可用
      </h1>
      <p className="mt-2 text-xs text-muted-foreground/70 tabular-nums">
        正在自动重连 · 检查于 {ago}
      </p>
    </div>
  );
}
