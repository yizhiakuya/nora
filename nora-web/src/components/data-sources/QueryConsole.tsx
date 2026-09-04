'use client';

import { useState, useEffect } from "react";
import { Play, History, Loader2, Download, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { MOCK_QUERIES } from "@/lib/devData";
import { useAutomations } from "@/hooks/useAutomations";
import { QueryHistory } from "@/types";
import { toast } from "sonner";

const AI_SUGGEST = "SELECT status, COUNT(*) as count FROM orders GROUP BY status ORDER BY count DESC;";

const RESULT_COLUMNS = ["status", "count"];
const RESULT_ROWS: string[][] = [
  ["paid", "5214"],
  ["shipped", "2380"],
  ["pending", "826"],
];

function downloadCsv() {
  const csv = [RESULT_COLUMNS.join(","), ...RESULT_ROWS.map((r) => r.join(","))].join("\n");
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
  initialSql?: string;
}

export function QueryConsole({ database, initialSql }: QueryConsoleProps) {
  const [sql, setSql] = useState(initialSql ?? "");
  const [isRunning, setIsRunning] = useState(false);
  const [hasRun, setHasRun] = useState(false);
  const [history, setHistory] = useState<QueryHistory[]>(MOCK_QUERIES);
  const addRule = useAutomations((s) => s.addRule);
  const [aiGenerating, setAiGenerating] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();

  useEffect(() => {
    if (initialSql) {
      setSql(initialSql);
    }
  }, [initialSql]);

  const handleRun = () => {
    if (!sql.trim() || isRunning) return;
    cancelAll();
    setIsRunning(true);
    schedule(() => {
      setIsRunning(false);
      setHasRun(true);
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
            placeholder="-- 输入 SQL，或点击下方 AI 生成"
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

      {!isRunning && hasRun && (
        <div className="bg-card border border-border rounded-xl overflow-hidden">
          <div className="px-4 py-2 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center justify-between">
            <span className="text-xs font-bold text-foreground">结果</span>
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-green-600 dark:text-green-400">✓ 3 rows · 8ms</span>
              <button
                type="button"
                onClick={downloadCsv}
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
          <table className="w-full text-xs">
            <thead>
              <tr className="bg-muted border-b border-border text-muted-foreground">
                <th className="p-2.5 text-left font-medium">status</th>
                <th className="p-2.5 text-left font-medium">count</th>
              </tr>
            </thead>
            <tbody>
              {RESULT_ROWS.map(([a, b]) => (
                <tr key={a} className="border-b border-border last:border-0 text-foreground">
                  <td className="p-2.5 font-mono">{a}</td>
                  <td className="p-2.5 tabular-nums">{b}</td>
                </tr>
              ))}
            </tbody>
          </table>
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
