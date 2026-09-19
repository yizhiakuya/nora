'use client';

import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Play, History, Loader2, Download, Zap, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { useAutomations } from "@/hooks/useAutomations";
import { QueryHistory } from "@/types";
import { toast } from "sonner";
import { datasourcesApi, type BackendQueryResult } from "@/lib/services/datasourcesApi";
import { USE_BACKEND } from "@/lib/api/client";

const AI_SUGGEST = "SELECT status, COUNT(*) as count FROM orders GROUP BY status ORDER BY count DESC;";

const RESULT_COLUMNS = ["status", "count"];
const RESULT_ROWS: string[][] = [
  ["paid", "5214"],
  ["shipped", "2380"],
  ["pending", "826"],
];

function downloadCsv(columns: string[], rows: (string | null)[][]) {
  const csv = [columns.join(","), ...rows.map((r) => r.map((c) => c ?? "").join(","))].join("\n");
  const blob = new Blob([`\uFEFF${csv}`], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `query_result_${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

interface QueryConsoleProps {
  database: string;
  /** 服务端连接 id(后端模式必传) */
  connectionId?: number;
  /** 引擎类型:redis 时执行只读 Redis 命令而非 SQL */
  engine?: string;
  initialSql?: string;
}

/**
 * 查询控制台:USE_BACKEND 时走 datasource-service 只读执行(限 200 行,
 * 非 SELECT/SHOW/EXPLAIN 会被后端 SqlGuard 拒绝),历史来自服务端;Mock 模式沿用模拟行为。
 */
export function QueryConsole({ database, connectionId, engine, initialSql }: QueryConsoleProps) {
  const navigate = useNavigate();
  const backendMode = USE_BACKEND && connectionId !== undefined;
  const isRedis = engine === "redis";
  const [sql, setSql] = useState(initialSql ?? "");
  const [isRunning, setIsRunning] = useState(false);
  const [hasRun, setHasRun] = useState(false);
  const [result, setResult] = useState<BackendQueryResult | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [history, setHistory] = useState<QueryHistory[]>([]);
  const addRule = useAutomations((s) => s.addRule);
  const { schedule, cancelAll } = useTimedSequence();

  useEffect(() => {
    if (initialSql) {
      setSql(initialSql);
    }
  }, [initialSql]);

  // 连接切换(仅切换时,挂载不清):清空上一次结果/错误,避免残留另一数据源的查询输出
  const prevConnectionRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    const prev = prevConnectionRef.current;
    prevConnectionRef.current = connectionId;
    if (prev === undefined || prev === connectionId) return;
    setSql("");
    setResult(null);
    setRunError(null);
    setHasRun(false);
    setHistory([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connectionId]);

  // 后端模式:拉取服务端查询历史
  const loadHistory = useCallback(async () => {
    if (!backendMode || connectionId === undefined) return;
    try {
      const rows = await datasourcesApi.fetchHistory(connectionId, 50);
      setHistory(rows.map((h) => ({
        id: h.id,
        sql: h.sql,
        duration: h.durationMs != null ? `${h.durationMs}ms` : "—",
        rowsAffected: h.rowsAffected,
        time: h.executedAt?.slice(5, 16).replace("T", " ") ?? "",
        status: h.status === "success" ? ("success" as const) : ("error" as const),
      })));
    } catch {
      /* 历史拉取失败不打断 */
    }
  }, [backendMode, connectionId]);

  useEffect(() => {
    if (backendMode) void loadHistory();
  }, [backendMode, loadHistory]);

  const handleRun = async () => {
    if (!sql.trim() || isRunning) return;
    cancelAll();
    setIsRunning(true);
    setRunError(null);
    if (backendMode && connectionId !== undefined) {
      try {
        const r = await datasourcesApi.runQuery(connectionId, sql.trim());
        setResult(r);
        setHasRun(true);
      } catch (e) {
        setRunError((e as Error).message);
        setResult(null);
        setHasRun(true);
      } finally {
        setIsRunning(false);
        void loadHistory();
      }
      return;
    }
    // Mock 模式
    schedule(() => {
      setIsRunning(false);
      setHasRun(true);
      setResult({ columns: RESULT_COLUMNS, rows: RESULT_ROWS, rowCount: 3, durationMs: 8 });
      setHistory((prev) => [
        { id: Date.now(), sql, duration: "8ms", rowsAffected: 3, time: "刚刚", status: "success" as const },
        ...prev,
      ].slice(0, 50));
    }, 800);
  };

  const saveAsAutomation = async () => {
    const saved = await addRule(
      isRedis ? "定时执行 Redis 命令" : "定时执行查询：订单状态分布",
      "每日 09:00",
      sql.trim() || AI_SUGGEST,
    );
    if (saved) {
      toast.success("已保存为自动任务（每日 09:00 执行），到「自动任务」页查看");
    }
    // 失败:store 已 toast 人话错误(2026-09-19 修假成功),这里不再重复提示
  };

  /** 让 AI 生成 SQL:跳到对话页预填(真实 agent 可读 schema 后写 SQL,不再本地假生成) */
  const askAi = () => {
    navigate(`/chat?prompt=${encodeURIComponent(isRedis
      ? `请基于 Redis 数据源「${database}」帮我写一条只读命令：`
      : `请基于数据源「${database}」的表结构帮我写一条 SQL：`)}`);
  };

  const displayColumns = backendMode ? result?.columns ?? [] : RESULT_COLUMNS;
  const displayRows = backendMode ? result?.rows ?? [] : RESULT_ROWS;

  return (
    <div className="space-y-4">
      {/* SQL Editor */}
      <div className="bg-card border border-border rounded-xl overflow-hidden">
        <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50">
          <span className="text-sm font-bold text-foreground font-mono">{database} › {isRedis ? "Redis 命令" : "SQL"}</span>
          <Button
            size="sm"
            className="h-8 text-[13px] bg-green-600 dark:bg-green-500 hover:bg-green-700 dark:hover:bg-green-600"
            onClick={handleRun}
            disabled={isRunning || !sql.trim()}
          >
            {isRunning ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <Play className="w-3.5 h-3.5 mr-1" />}
            运行
          </Button>
        </div>
        <div className="relative">
          <textarea
            rows={4}
            className="w-full bg-[#1e1e1e] text-gray-300 font-mono text-[13px] leading-relaxed resize-none p-4 focus:outline-none custom-scroll"
            placeholder={isRedis
              ? "只读命令（GET / HGETALL / KEYS / SCAN / TYPE / TTL / LRANGE / SMEMBERS / ZRANGE / INFO …）"
              : "-- 只读查询（SELECT / SHOW / EXPLAIN），或点击下方 AI 生成"}
            value={sql}
            onChange={(e) => setSql(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handleRun(); }}
          />
        </div>
        <div className="px-4 py-2 border-t border-border flex items-center gap-2">
          <button
            type="button"
            onClick={askAi}
            className="text-[11px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer flex items-center gap-1"
          >
            <Sparkles className="w-2.5 h-2.5" /> {isRedis ? "让 AI 写命令（跳转对话）" : "让 AI 写 SQL（跳转对话，可读表结构）"}
          </button>
          <span className="text-[11px] text-muted-foreground ml-auto">⌘+Enter 运行</span>
        </div>
      </div>

      {/* Result */}
      {isRunning && (
        <div className="py-8 flex items-center justify-center gap-2 text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" />
          <span className="text-xs">执行中…</span>
        </div>
      )}

      {!isRunning && hasRun && runError && (
        <div className="bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-xl px-4 py-3">
          <div className="text-xs font-bold text-red-600 dark:text-red-400 mb-1">查询失败</div>
          <div className="text-xs font-mono text-red-600/80 dark:text-red-400/80 break-all">{runError}</div>
        </div>
      )}

      {!isRunning && hasRun && !runError && result && (
        <div className="bg-card border border-border rounded-xl overflow-hidden">
          <div className="px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center justify-between">
            <span className="text-[13px] font-bold text-foreground">结果</span>
            <div className="flex items-center gap-2">
              <span className="text-[11px] text-green-600 dark:text-green-400">
                ✓ {result.rowCount} rows · {result.durationMs}ms
              </span>
              <button
                type="button"
                onClick={() => downloadCsv(displayColumns, displayRows)}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium bg-muted text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 transition-colors cursor-pointer"
              >
                <Download className="w-2.5 h-2.5" /> 导出 CSV
              </button>
              <button
                type="button"
                onClick={saveAsAutomation}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 hover:bg-blue-100 dark:hover:bg-blue-900/60 transition-colors cursor-pointer"
              >
                <Zap className="w-2.5 h-2.5" /> 保存为自动任务
              </button>
            </div>
          </div>
          <div className="max-h-80 overflow-y-auto custom-scroll">
            <table className="w-full text-[13px]">
              <thead className="sticky top-0 z-10">
                <tr className="bg-muted border-b border-border text-muted-foreground">
                  {displayColumns.map((c) => (
                    <th key={c} className="p-3 text-left font-medium bg-muted">{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {displayRows.map((row, i) => (
                  <tr key={i} className="border-b border-border last:border-0 text-foreground">
                    {row.map((cell, j) => (
                      <td key={j} className="p-3 font-mono">
                        {cell ?? <span className="text-muted-foreground italic">NULL</span>}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* History */}
      <div className="bg-card border border-border rounded-xl overflow-hidden">
        <div className="px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center gap-1.5">
          <History className="w-4 h-4 text-muted-foreground" />
          <span className="text-[13px] font-bold text-foreground">查询历史</span>
          <span className="text-[11px] text-muted-foreground ml-auto">{history.length} 条</span>
        </div>
        <div className="divide-y divide-gray-100 dark:divide-gray-800 max-h-48 overflow-y-auto custom-scroll">
          {history.length === 0 ? (
            <div className="py-8 flex flex-col items-center text-muted-foreground gap-1.5">
              <History className="w-6 h-6 opacity-20" />
              <span className="text-[13px]">暂无查询历史</span>
            </div>
          ) : (
            history.map((q) => (
              <button
                key={q.id}
                type="button"
                onClick={() => setSql(q.sql)}
                className="w-full px-4 py-2.5 text-left hover:bg-muted/50 transition-colors cursor-pointer group"
              >
                <div className="flex items-center justify-between mb-0.5">
                  <span className={`text-[11px] font-medium ${q.status === "success" ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400"}`}>
                    {q.status === "success" ? `✓ ${q.rowsAffected} rows · ${q.duration}` : "✗ ERROR"}
                  </span>
                  <span className="text-[11px] text-muted-foreground">{q.time}</span>
                </div>
                <code className="text-xs font-mono text-muted-foreground truncate block group-hover:text-blue-600 dark:group-hover:text-blue-400">
                  {q.sql}
                </code>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
