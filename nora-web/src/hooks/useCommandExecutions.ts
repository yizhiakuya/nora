import { create } from "zustand";
import { useEffect, useState } from "react";
import { commandExecutionsApi } from "@/lib/services/commandExecutionsApi";
import type { CommandExecution } from "@/types";

export const useCommandExecutions = create<{
  sessionId: string;
  items: CommandExecution[];
  error: string | null;
}>(() => ({ sessionId: "", items: [], error: null }));

/** 一个侧面工作区拥有一个列表轮询，不随工具行重复订阅。 */
export function useCommandExecutionPolling(sessionId: string) {
  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    useCommandExecutions.setState({ sessionId, items: [], error: null });
    const poll = async () => {
      if (!sessionId) return;
      try {
        const items = await commandExecutionsApi.list(sessionId);
        if (!disposed) useCommandExecutions.setState({ items, error: null });
      } catch (error) {
        if (!disposed) useCommandExecutions.setState({ error: error instanceof Error ? error.message : String(error) });
      } finally {
        if (!disposed) timer = setTimeout(() => void poll(), 1000);
      }
    };
    void poll();
    return () => { disposed = true; clearTimeout(timer); };
  }, [sessionId]);
}

/** 仅展开的输出区域读取日志快照；慢请求不叠加下一次请求。 */
export function useCommandOutput(execution: CommandExecution) {
  const [snapshot, setSnapshot] = useState(execution);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const current = await commandExecutionsApi.get(execution.sessionId, execution.executionId);
        if (disposed) return;
        setSnapshot(current); setError(null);
        if (current.finishedAt == null) timer = setTimeout(() => void poll(), 500);
      } catch (error) {
        if (!disposed) { setError(error instanceof Error ? error.message : String(error)); timer = setTimeout(() => void poll(), 2000); }
      }
    };
    void poll();
    return () => { disposed = true; clearTimeout(timer); };
  }, [execution.sessionId, execution.executionId]);
  return { snapshot, error };
}
