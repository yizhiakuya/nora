'use client';

import { useCallback, useEffect, useState } from "react";
import { Play, History, Loader2, Download, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { MOCK_QUERIES } from "@/lib/devData";
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
  const blob = new Blob([`﻿${csv}`], { type: "text/csv;charset=utf-8" });
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
  initialSql?: string;
}

/**
 * 查询控制台:USE_BACKEND 时走 datasource-service 只读执行(限 200 行,
 * 非 SELECT/SHOW/EXPLAIN 会被后端 SqlGuard 拒绝),历史来自服务端;Mock 模式沿用模拟行为。
 */
export function QueryConsole({ database, connectionId, initialSql }: QueryConsoleProps) {
  const backendMode = USE_BACKEND && connectionId !== undefined;
  const [sql, setSql] = useState(initialSql ?? "");
  const [isRunning, setIsRunning] = useState(false);
  const [hasRun, setHasRun] = useState(false);
  const [result, setResult] = useState<BackendQueryResult | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [history, setHistory] = useState<QueryHistory[]>(MOCK_QUERIES);
  const addRule = useAutomations((s) => s.addRule);
  const [aiGenerating, setAiGenerating] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();

  useEffect(() => {
    if (initialSql) {
      setSql(initialSql);
    }
  }, [initialSql]);

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

  const saveAsAutomation = () => {
    addRule(
      "定时执行查询：订单状态分布",
      "每日 09:00",
      sql.trim() || AI_SUGGEST,
    );
    toast.success("已保存为自动任务（每日 09:00 执行），到「自动任务」页查看");
  };

  /** AI 生成 SQL：模拟分析表结构 → 逐段输出 */
  const generateWithAI = () => {
    if (aiGenerating) return;
    setAiGenerating(true);
    setSql("");
    const text = AI_SUGGEST;
    let i = 0;
    const timer = setInterval(() => {
      i += 8;
      setSql(text.slice(0, i));
      if (i >= text.length) {
        clearInterval(timer);
        setAiGenerating(false);
        toast.success("SQL 已生成，可编辑后运行");
      }
    }, 40);
  };

  const displayColumns = backendMode ? result?.columns ?? [] : RESULT_COLUMNS;
  const displayRows = backendMode ? result?.rows ?? [] : RESULT_ROWS;

  return (
    <div className="space-y-4">
      {/* SQL Editor */}
      <div className="bg-card border border-border rounded-xl overflow-hidden">
        <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50">
          <span className="text-xs font-bold text-foreground font-mono">{database} › SQL</span>
          <Button
            size="sm"
            className="h-7 text-xs bg-green-600 dark:bg-green-500 hover:bg-green-700 dark:hover:bg-green-600"
            onClick={handleRun}
            disabled={isRunning || !sql.trim()}
          >
            {isRunning ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : <Play className="w-3 h-3 mr-1" />}
            运行
          </Button>
        </div>
        <div className="relative">
          <textarea
            rows={4}
            className="w-full bg-[#1e1e1e] text-gray-300 font-mono text-xs leading-relaxed resize-none p-4 focus:outline-none custom-scroll"
            placeholder="-- 只读查询（SELECT / SHOW / EXPLAIN），或点击下方 AI 生成"
            value={sql}
            onChange={(e) => setSql(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handleRun(); }}
          />
        </div>
        <div className="px-4 py-2 border-t border-border flex items-center gap-2">
          <button
            type="button"
            onClick={generateWithAI}
            disabled={aiGenerating}
            className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer flex items-center gap-1 disabled:opacity-60"
          >
            {aiGenerating ? "✨ AI 正在分析表结构并生成…" : "✨ AI 生成：按状态统计订单数"}
          </button>
          <span className="text-[10px] text-muted-foreground ml-auto">⌘+Enter 运行</span>
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
            <span className="text-xs font-bold text-foreground">结果</span>
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-green-600 dark:text-green-400">
                ✓ {result.rowCount} rows · {result.durationMs}ms
              </span>
              <button
                type="button"
                onClick={() => downloadCsv(displayColumns, displayRows)}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-muted text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 transition-colors cursor-pointer"
              >
                <Download className="w-2.5 h-2.5" /> 导出 CSV
              </button>
              <button
                type="button"
                onClick={saveAsAutomation}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 hover:bg-blue-100 dark:hover:bg-blue-900/60 transition-colors cursor-pointer"
              >
                <Zap className="w-2.5 h-2.5" /> 保存为自动任务
              </button>
            </div>
          </div>
          <div className="max-h-80 overflow-y-auto custom-scroll">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-muted border-b border-border text-muted-foreground">
                  {displayColumns.map((c) => (
                    <th key={c} className="p-2.5 text-left font-medium">{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {displayRows.map((row, i) => (
                  <tr key={i} className="border-b border-border last:border-0 text-foreground">
                    {row.map((cell, j) => (
                      <td key={j} className="p-2.5 font-mono">
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
          <History className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-bold text-foreground">查询历史</span>
          <span className="text-[10px] text-muted-foreground ml-auto">{history.length} 条</span>
        </div>
        <div className="divide-y divide-gray-100 dark:divide-gray-800 max-h-48 overflow-y-auto custom-scroll">
          {history.map((q) => (
            <button
              key={q.id}
              type="button"
              onClick={() => setSql(q.sql)}
              className="w-full px-4 py-2 text-left hover:bg-muted/50 transition-colors cursor-pointer group"
            >
              <div className="flex items-center justify-between mb-0.5">
                <span className={`text-[10px] font-medium ${q.status === "success" ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400"}`}>
                  {q.status === "success" ? `✓ ${q.rowsAffected} rows · ${q.duration}` : "✗ ERROR"}
                </span>
                <span className="text-[10px] text-muted-foreground">{q.time}</span>
              </div>
              <code className="text-[11px] font-mono text-muted-foreground truncate block group-hover:text-blue-600 dark:group-hover:text-blue-400">
                {q.sql}
              </code>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
