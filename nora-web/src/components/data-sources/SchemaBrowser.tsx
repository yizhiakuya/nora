'use client';

import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Table2, RefreshCw, Loader2, Database, Play, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

import { datasourcesApi, type BackendColumn, type BackendTable } from "@/lib/services/datasourcesApi";
import { USE_BACKEND } from "@/lib/api/client";

interface SchemaBrowserProps {
  database: string;
  /** 服务端连接 id(后端模式必传) */
  connectionId?: number;
  /** 引擎类型:redis 时表=key,查询命令按 key 类型生成 */
  engine?: string;
  onQueryTable?: (tableName: string) => void;
}

/**
 * Schema 浏览:USE_BACKEND 时走 datasource-service 的 DatabaseMetaData。
 * 布局:左侧表列表(可过滤) + 右侧选中表的字段详情,卡片限高内部滚动,
 * 表多/字段多时不再把整页撑成长滚动。
 */
export function SchemaBrowser({ database, connectionId, engine, onQueryTable }: SchemaBrowserProps) {
  // 未选连接时显示空态(不提供假 schema 数据)
  const backendMode = USE_BACKEND && connectionId !== undefined;
  const [serverTables, setServerTables] = useState<BackendTable[] | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  /** 表唯一键:schema 内同名表(如各 schema 的 flyway_schema_history)靠 schema 区分 */
  const tableKey = (t: { schema?: string | null; name: string }) => `${t.schema ?? ""}.${t.name}`;

  const sync = useCallback(async (opts?: { silent?: boolean }) => {
    if (!backendMode || connectionId === undefined) return;
    setSyncing(true);
    try {
      const tables = await datasourcesApi.fetchSchema(connectionId);
      setServerTables(tables);
      setSelectedKey(tables[0] ? tableKey(tables[0]) : null);
      if (!opts?.silent) toast.success(`已同步 ${tables.length} 张表结构`);
    } catch (e) {
      toast.error(`同步失败：${(e as Error).message}`);
    } finally {
      setSyncing(false);
    }
  }, [backendMode, connectionId]);

  // 连接切换:清空上一连接的 schema,避免显示旧数据(Redis 与 PG 表结构完全不同)
  useEffect(() => {
    setServerTables(null);
    setSelectedKey(null);
    setFilter("");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backendMode, connectionId]);

  // 后端模式:首次进入/切换连接后自动拉取(静默,不弹 toast;手动点「同步 Schema」才提示)
  useEffect(() => {
    if (backendMode && serverTables === null && !syncing) {
      void sync({ silent: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backendMode, connectionId, serverTables]);

  const tables: { schema?: string | null; name: string; comment: string; columns: BackendColumn[] }[] =
    useMemo(
      () => (serverTables ?? []).map((t) => ({ schema: t.schema, name: t.name, comment: t.comment ?? "", columns: t.columns })),
      [serverTables]
    );

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return tables;
    return tables.filter((t) => t.name.toLowerCase().includes(q));
  }, [tables, filter]);

  /** 按 schema 分组(保持后端返回顺序),同名表不再混淆 */
  const groups = useMemo<[string, typeof tables][]>(() => {
    const map = new Map<string, typeof tables>();
    for (const t of filtered) {
      const key = t.schema ?? "";
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(t);
    }
    return Array.from(map.entries());
  }, [filtered]);

  // 过滤后选中项回落第一个匹配,保证右侧详情与左侧高亮始终一致
  const selectedTable = useMemo(() => {
    if (filtered.length === 0) return null;
    return filtered.find((t) => tableKey(t) === selectedKey) ?? filtered[0];
  }, [filtered, selectedKey]);

  /** 生成可执行的表引用:非默认 schema 需要 schema 前缀,否则查询不到 */
  const qualifiedName = (t: { schema?: string | null; name: string }) =>
    t.schema && t.schema !== "public" ? `${t.schema}.${t.name}` : t.name;

  /**
   * 查询命令:JDBC 走 SELECT;Redis 的"表"是 key,按 key 类型生成对应只读命令
   * (类型信息编码在 comment 里,由后端 RedisSchema 生成,如 "类型 hash · TTL -1s · 3 项")。
   */
  const queryCommandFor = (t: { schema?: string | null; name: string; comment: string }) => {
    if (engine !== "redis") {
      return `SELECT * FROM ${qualifiedName(t)} LIMIT 20;`;
    }
    const type = /类型\s+(\w+)/.exec(t.comment)?.[1] ?? "string";
    const key = t.name;
    switch (type) {
      case "hash": return `HGETALL ${key}`;
      case "list": return `LRANGE ${key} 0 -1`;
      case "set": return `SMEMBERS ${key}`;
      case "zset": return `ZRANGE ${key} 0 -1`;
      case "stream": return `XRANGE ${key} - +`;
      default: return `GET ${key}`;
    }
  };

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden flex flex-col max-h-[calc(100vh-300px)] min-h-[380px]">
      <div className="p-3 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 shrink-0">
        <div className="flex items-center justify-between gap-3">
          <span className="text-sm font-bold text-foreground shrink-0">
            {database} · {tables.length} 张表
          </span>
          <div className="flex items-center gap-2 min-w-0">
            {tables.length > 0 && (
              <div className="relative hidden sm:block">
                <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
                <Input
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  placeholder="过滤表名…"
                  className="h-7 w-44 text-xs pl-6 pr-2 bg-card"
                />
              </div>
            )}
            {backendMode ? (
              <Button variant="outline" size="sm" className="h-7 text-[11px] px-2.5 shrink-0" onClick={() => void sync()} disabled={syncing}>
                {syncing ? <Loader2 className="w-3 h-3 animate-spin" /> : <RefreshCw className="w-3 h-3" />}
                同步 Schema
              </Button>
            ) : null}
          </div>
        </div>
      </div>
      {tables.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground gap-2">
          <Database className="w-8 h-8 opacity-20" />
          <span className="text-xs">
            {syncing ? "正在拉取表结构…" : "尚未同步表结构，点击右上「同步 Schema」拉取"}
          </span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground gap-1.5">
          <Search className="w-6 h-6 opacity-20" />
          <span className="text-xs">没有匹配「{filter}」的表</span>
        </div>
      ) : (
      <div className="flex flex-1 min-h-0">
        {/* 左侧:表列表(按 schema 分组,同名表可区分) */}
        <div className="w-80 shrink-0 border-r border-border overflow-y-auto custom-scroll py-1">
          {groups.map(([schemaName, groupTables]) => (
            <div key={schemaName || "(default)"}>
              {schemaName && (
                <div className="px-3 pt-3 pb-1.5 text-[11px] font-semibold text-muted-foreground/80 uppercase tracking-wide">
                  {schemaName}
                </div>
              )}
              {groupTables.map((table) => {
                const active = selectedTable ? tableKey(selectedTable) === tableKey(table) : false;
                return (
                  <button
                    key={tableKey(table)}
                    type="button"
                    onClick={() => setSelectedKey(tableKey(table))}
                    className={`w-full flex items-start gap-2 pl-3 pr-2.5 py-2 text-left transition-colors cursor-pointer border-l-2 ${active ? "bg-blue-50 dark:bg-blue-950/40 border-blue-500" : "border-transparent hover:bg-muted/60"}`}
                    title={table.comment || undefined}
                  >
                    <Table2 className={`w-4 h-4 shrink-0 mt-0.5 ${active ? "text-blue-600 dark:text-blue-400" : "text-muted-foreground"}`} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1.5">
                        <span className={`text-[13px] font-mono truncate ${active ? "text-blue-700 dark:text-blue-300 font-medium" : "text-foreground"}`}>
                          {table.name}
                        </span>
                        <span className="ml-auto text-[11px] text-muted-foreground tabular-nums shrink-0" title={`${table.columns.length} 个字段`}>
                          {table.columns.length}
                        </span>
                      </span>
                      {table.comment && (
                        <span className="block text-[11px] text-muted-foreground/80 truncate leading-snug mt-0.5">
                          {table.comment}
                        </span>
                      )}
                    </span>
                  </button>
                );
              })}
            </div>
          ))}
        </div>

        {/* 右侧:字段详情 */}
        <div className="flex-1 min-w-0 flex flex-col">
          <div className="px-4 py-2 border-b border-border bg-muted/30 flex items-center justify-between gap-3 shrink-0">
            <div className="min-w-0">
              <div className="text-sm font-bold font-mono text-foreground truncate">
                {selectedTable?.schema && (
                  <span className="font-sans font-normal text-[11px] text-muted-foreground mr-1.5">{selectedTable.schema}.</span>
                )}
                {selectedTable?.name}
                <span className="ml-2 font-sans font-normal text-[11px] text-muted-foreground">
                  {selectedTable?.columns.length} 个字段
                </span>
              </div>
              {selectedTable?.comment && (
                <div className="text-[11px] text-muted-foreground truncate mt-0.5" title={selectedTable.comment}>
                  {selectedTable.comment}
                </div>
              )}
            </div>
            {onQueryTable && (
              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs px-3 shrink-0 bg-card hover:bg-muted text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-900"
                onClick={() => onQueryTable(queryCommandFor(selectedTable!))}
              >
                <Play className="w-3 h-3 mr-1" /> {engine === "redis" ? "在控制台查看此键" : "在控制台查询此表"}
              </Button>
            )}
          </div>
          <div className="flex-1 overflow-y-auto custom-scroll">
            <table className="w-full text-[13px]">
              <thead className="sticky top-0 z-10">
                <tr className="text-muted-foreground border-b border-border bg-card">
                  <th className="py-2.5 pl-4 pr-3 font-medium text-left">字段</th>
                  <th className="py-2.5 pr-3 font-medium text-left whitespace-nowrap">类型</th>
                  <th className="py-2.5 pr-3 font-medium text-left">注释</th>
                  <th className="py-2.5 pr-4 font-medium text-left">默认值</th>
                </tr>
              </thead>
              <tbody>
                {selectedTable?.columns.map((col) => (
                  <tr key={col.name} className="border-b border-border last:border-0 hover:bg-muted/40">
                    <td className="py-2 pl-4 pr-3 font-mono text-foreground">
                      <span className="inline-flex items-center gap-1.5">
                        {col.name}
                        {col.primaryKey && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-sans font-semibold bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-400" title="主键">
                            PK
                          </span>
                        )}
                        {!col.nullable && !col.primaryKey && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-sans font-medium bg-muted text-muted-foreground" title="NOT NULL">
                            NN
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="py-2 pr-3 font-mono text-muted-foreground whitespace-nowrap">{col.type}</td>
                    <td className="py-2 pr-3 text-muted-foreground">
                      {col.comment || <span className="opacity-40">—</span>}
                    </td>
                    <td className="py-2 pr-4 font-mono text-muted-foreground/80 max-w-[200px] truncate" title={col.defaultValue ?? undefined}>
                      {col.defaultValue ?? <span className="opacity-40">—</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      )}
    </div>
  );
}
