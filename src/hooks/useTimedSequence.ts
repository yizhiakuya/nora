import { useCallback, useEffect, useRef } from "react";

/**
 * 统一管理 setTimeout 序列：
 * - 组件卸载时自动清理所有未触发的定时器，并阻止回调执行
 * - 所有"延时状态机"（上传、发布、搜索模拟等）都应使用此 hook，避免卸载后 setState 与时序错乱
 */
export function useTimedSequence() {
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      timers.current.forEach(clearTimeout);
      timers.current = [];
    };
  }, []);

  const schedule = useCallback((fn: () => void, ms: number) => {
    const id = setTimeout(() => {
      if (mountedRef.current) fn();
    }, ms);
    timers.current.push(id);
    return id;
  }, []);

  const cancelAll = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  }, []);

  return { schedule, cancelAll };
}
