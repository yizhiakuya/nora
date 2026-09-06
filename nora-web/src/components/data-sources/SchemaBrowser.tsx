'use client';

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { ChevronRight, Table2, RefreshCw, Loader2, Database, Play } from "lucide-react";
import { Button } from "@/components/ui/button";

import { datasourcesApi, type BackendTable } from "@/lib/services/datasourcesApi";
import { USE_BACKEND } from "@/lib/api/client";

interface SchemaBrowserProps {
  database: string;
  /** 服务端连接 id(后端模式必传) */
  connectionId?: number;
  onQueryTable?: (tableName: string) => void;
}

/**
 * Schema 浏览:USE_BACKEND 时走 datasource-service 的 DatabaseMetaData
 */
export function SchemaBrowser({ database, connectionId, onQueryTable }: SchemaBrowserProps) {
  // 未选连接时显示空态(不提供假 schema 数据)
  const backendMode = USE_BACKEND && connectionId !== undefined;
  const [serverTables, setServerTables] = useState<BackendTable[] | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  const sync = useCallback(async () => {
    if (!backendMode || connectionId === undefined) return;
    setSyncing(true);
    try {
      const tables = await datasourcesApi.fetchSchema(connectionId);
      setServerTables(tables);
      setExpanded(tables[0]?.name ?? null);
      toast.success(`已同步 ${tables.length} 张表结构`);
    } catch (e) {
      toast.error(`同步失败：${(e as Error).message}`);
    } finally {
      setSyncing(false);
    }
  }, [backendMode, connectionId]);

  // 后端模式:首次进入自动拉取
  useEffect(() => {
    if (backendMode && serverTables === null && !syncing) {
      void sync();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backendMode, connectionId]);

  const tables: { name: string; columns: { name: string; type: string }[] }[] =
    (serverTables ?? []).map((t) => ({ name: t.name, columns: t.columns }));

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      <div className="p-3 border-b border-border bg-gray-50/50 dark:bg-gray-950/50">
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold text-foreground">
            {database} · {tables.length} 张表
          </span>
          {backendMode ? (
            <Button variant="outline" size="sm" className="h-6 text-[10px] px-2" onClick={sync} disabled={syncing}>
              {syncing ? <Loader2 className="w-2.5 h-2.5 animate-spin" /> : <RefreshCw className="w-2.5 h-2.5" />}
              同步 Schema
            </Button>
          ) : null}
        </div>
      </div>
      {tables.length === 0 ? (
        <div className="py-16 flex flex-col items-center text-muted-foreground gap-2">
          <Database className="w-8 h-8 opacity-20" />
          <span className="text-xs">
            {syncing ? "正在拉取表结构…" : "尚未同步表结构，点击右上「同步 Schema」拉取"}
          </span>
        </div>
      ) : (
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {tables.map((table) => {
          const isOpen = expanded === table.name;
          return (
            <div key={table.name}>
              <button
                type="button"
                onClick={() => setExpanded(isOpen ? null : table.name)}
                className="w-full flex items-center gap-2.5 px-4 py-2.5 hover:bg-muted/50 transition-colors cursor-pointer text-left"
              >
                <ChevronRight className={`w-3.5 h-3.5 text-muted-foreground transition-transform ${isOpen ? "rotate-90" : ""}`} />
                <Table2 className="w-4 h-4 text-blue-600 dark:text-blue-400 shrink-0" />
                <span className="text-sm font-medium text-foreground font-mono">{table.name}</span>
                <span className="ml-auto text-[10px] text-muted-foreground tabular-nums">
                  {table.columns.length} 字段
                </span>
              </button>
              {isOpen && (
                <div className="bg-muted/50 px-4 pb-3">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-muted-foreground border-b border-border">
                        <th className="py-1.5 pr-3 font-medium text-left">字段</th>
                        <th className="py-1.5 pr-3 font-medium text-left">类型</th>
                      </tr>
                    </thead>
                    <tbody>
                      {table.columns.map((col) => (
                        <tr key={col.name} className="border-b border-border last:border-0">
                          <td className="py-1.5 pr-3 font-mono text-foreground">{col.name}</td>
                          <td className="py-1.5 text-muted-foreground font-mono">{col.type}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {onQueryTable && (
                    <div className="mt-2.5 flex justify-end">
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-6 text-[11px] px-2.5 bg-card hover:bg-muted text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-900"
                        onClick={() => onQueryTable(table.name)}
                      >
                        <Play className="w-2.5 h-2.5 mr-1" /> 在控制台查询此表
                      </Button>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
      )}
    </div>
  );
}
