import { useEffect, useState } from "react";

/** 概览自动刷新；旧数据保留在错误提示旁，不把网络失败变成空列表。 */
export function usePollingResource<T>(load: () => Promise<T>) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let disposed = false;
    let running = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      clearTimeout(timer);
      if (disposed || running || document.hidden) return;
      running = true;
      try {
        const result = await load();
        if (!disposed) { setData(result); setError(undefined); }
      } catch (failure) {
        if (!disposed) setError(failure instanceof Error ? failure.message : String(failure));
      } finally {
        running = false;
        if (!disposed) { setLoading(false); timer = setTimeout(() => void poll(), 5000); }
      }
    };
    const refresh = () => { void poll(); };
    refresh();
    document.addEventListener("visibilitychange", refresh);
    window.addEventListener("focus", refresh);
    return () => {
      disposed = true; clearTimeout(timer);
      document.removeEventListener("visibilitychange", refresh);
      window.removeEventListener("focus", refresh);
    };
  }, [load, revision]);
  return { data, error, loading, refresh: () => setRevision(value => value + 1) };
}
