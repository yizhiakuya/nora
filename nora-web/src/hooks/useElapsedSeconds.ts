import { useEffect, useState } from "react";

/**
 * 流式响应实时计时:返回从 startedAtMs 起经过的秒数,每秒递增;
 * startedAtMs 为空(历史消息/已结束)返回 null,不渲染计时。
 * timer 只在计时期间存活,结束后自动停表。
 */
export function useElapsedSeconds(startedAtMs: number | undefined, active: boolean): number | null {
  const [elapsed, setElapsed] = useState<number | null>(null);

  useEffect(() => {
    if (!startedAtMs || !active) {
      setElapsed(null);
      return;
    }
    const tick = () => setElapsed(Math.max(0, Math.round((Date.now() - startedAtMs) / 1000)));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [startedAtMs, active]);

  return elapsed;
}
