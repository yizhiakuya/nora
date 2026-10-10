'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { AlertTriangle, Table2, RefreshCw, Loader2, Database, Play, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

import { useConnections } from "@/hooks/useConnections";
import { datasourcesApi, type BackendColumn, type BackendTable } from "@/lib/services/datasourcesApi";

interface SchemaBrowserProps {
  database: string;
  /** 服务端连接 id(后端模式必传) */
  connectionId?: number;
  /** 引擎类型:redis 时表=key,查询命令按 key 类型生成 */
  engine?: string;
  onQueryTable?: (tableName: string) => void;
}

/**
 * Schema 浏览:走 datasource-service 的 DatabaseMetaData。
 * 布局:左侧表列表(可过滤) + 右侧选中表的字段详情,卡片限高内部滚动,
 * 表多/字段多时不再把整页撑成长滚动。
 */
export function SchemaBrowser({ database, connectionId, engine, onQueryTable }: SchemaBrowserProps) {
  // 未选连接时显示空态(不提供假 schema 数据)
  const backendMode = connectionId !== undefined;
  const [serverTables, setServerTables] = useState<BackendTable[] | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<symbol | null>(null);
  const markStatus = useConnections((s) => s.markStatus);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  /** 表唯一键:schema 内同名表(如各 schema 的 flyway_schema_history)靠 schema 区分 */
  const tableKey = (t: { schema?: string | null; name: string }) => `${t.schema ?? ""}.${t.name}`;

  const sync = useCallback(async (opts?: { silent?: boolean }) => {
    if (connectionId === undefined) return;
    const current = Symbol();
    request.current = current;
    setSyncing(true);
    setError(null);
    try {
      const tables = await datasourcesApi.fetchSchema(connectionId);
      if (current !== request.current) return;
      setServerTables(tables);
      setSelectedKey(tables[0] ? tableKey(tables[0]) : null);
      markStatus(connectionId, "connected");
      if (!opts?.silent) toast.success(`已同步 ${tables.length} ${engine === "redis" ? "个键" : "张表结构"}`);
    } catch (e) {
      if (current !== request.current) return;
      setError(e instanceof Error ? e.message : String(e));
      markStatus(connectionId, "error");
    } finally {
      if (current === request.current) setSyncing(false);
    }
  }, [connectionId, engine, markStatus]);

  // 进入页面时加载一次；关闭或切换连接后忽略尚未完成的请求。
  useEffect(() => {
    void sync({ silent: true });
    return () => { request.current = null; };
  }, [sync]);

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
   * 查询目标:JDBC 传表引用，由控制台生成 SELECT；Redis 的"表"是 key,按 key 类型生成对应只读命令
   * (类型信息编码在 comment 里,由后端 RedisSchema 生成,如 "类型 hash · TTL -1s · 3 项")。
   */
  const queryTargetFor = (t: { schema?: string | null; name: string; comment: string }) => {
    if (engine !== "redis") {
      return qualifiedName(t);
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
    <div className="surface overflow-hidden flex flex-col lg:max-h-[calc(100dvh-300px)] min-h-[380px]">
      <div className="p-4 border-b border-border bg-muted/30 shrink-0">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm font-semibold text-foreground shrink-0">
            {database} · {serverTables === null ? "表结构" : `${tables.length} ${engine === "redis" ? "个键" : "张表"}`}
          </span>
          <div className="flex flex-wrap items-center gap-2 min-w-0">
            {tables.length > 0 && (
              <div className="relative">
                <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
                <Input
                  aria-label="过滤表名或键名"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  placeholder="过滤表名…"
                  className="h-8 w-36 text-xs pl-6 pr-2 bg-card"
                />
              </div>
            )}
            {backendMode ? (
              <Button variant="outline" size="sm" className="h-8 text-xs px-3 shrink-0" onClick={() => void sync()} disabled={syncing}>
                {syncing ? <Loader2 className="w-3 h-3 animate-spin" /> : <RefreshCw className="w-3 h-3" />}
                同步 Schema
              </Button>
            ) : null}
          </div>
        </div>
      </div>
      {error !== null ? (
        <div role="alert" className="m-4 flex items-start gap-3 rounded-lg border border-destructive/20 bg-destructive/5 p-4 text-destructive">
          <AlertTriangle className="mt-0.5 w-4 h-4 shrink-0" />
          <pre className="min-w-0 whitespace-pre-wrap break-words text-[13px] leading-relaxed">{error}</pre>
        </div>
      ) : tables.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground gap-2">
          <Database className="w-8 h-8 opacity-20" />
          <span className="text-xs">
            {syncing ? "正在读取表结构…" : serverTables === null ? "尚未加载表结构" : engine === "redis" ? "当前数据库没有可浏览的键" : "当前数据库没有可浏览的表"}
          </span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground gap-1.5">
          <Search className="w-6 h-6 opacity-20" />
          <span className="text-xs">没有匹配「{filter}」的表</span>
        </div>
      ) : (
      <div className="flex flex-col lg:flex-row flex-1 min-h-0">
        {/* 左侧:表列表(按 schema 分组,同名表可区分) */}
        <div className="w-full lg:w-72 max-h-48 lg:max-h-none shrink-0 border-b lg:border-b-0 lg:border-r border-border overflow-y-auto custom-scroll py-1">
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
                    className={`w-full flex items-start gap-2 pl-3 pr-2.5 py-2 text-left transition-colors cursor-pointer border-l-2 ${active ? "bg-primary/5 border-primary" : "border-transparent hover:bg-muted/60"}`}
                    title={table.comment || undefined}
                  >
                    <Table2 className={`w-4 h-4 shrink-0 mt-0.5 ${active ? "text-primary" : "text-muted-foreground"}`} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1.5">
                        <span className={`text-[13px] font-mono truncate ${active ? "text-primary font-medium" : "text-foreground"}`}>
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
          <div className="px-4 py-3 border-b border-border bg-muted/30 flex flex-col items-start gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3 shrink-0">
            <div className="min-w-0 max-w-full">
              <div className="text-sm font-semibold font-mono text-foreground truncate">
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
                className="h-8 text-xs px-3 shrink-0 text-primary"
                onClick={() => onQueryTable(queryTargetFor(selectedTable!))}
              >
                <Play className="w-3 h-3 mr-1" /> {engine === "redis" ? "在控制台查看此键" : "在控制台查询此表"}
              </Button>
            )}
          </div>
          <div className="flex-1 max-h-80 lg:max-h-none overflow-auto custom-scroll">
            <table className="w-full min-w-[640px] text-[13px]">
              <thead className="sticky top-0 z-10">
                <tr className="text-muted-foreground border-b border-border bg-card">
                  <th className="py-2.5 pl-4 pr-3 font-medium text-left">字段</th>
                  <th className="py-2.5 pr-3 font-medium text-left whitespace-nowrap">类型</th>
                  <th className="py-2.5 pr-3 font-medium text-left whitespace-nowrap">注释</th>
                  <th className="py-2.5 pr-4 font-medium text-left whitespace-nowrap">默认值</th>
                </tr>
              </thead>
              <tbody>
                {selectedTable?.columns.map((col) => (
                  <tr key={col.name} className="border-b border-border last:border-0 hover:bg-muted/40">
                    <td className="py-2 pl-4 pr-3 font-mono text-foreground">
                      <span className="inline-flex items-center gap-1.5">
                        {col.name}
                        {col.primaryKey && (
                          <span className="px-1.5 py-0.5 rounded text-[11px] font-sans font-semibold bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-400" title="主键">
                            PK
                          </span>
                        )}
                        {!col.nullable && !col.primaryKey && (
                          <span className="px-1.5 py-0.5 rounded text-[11px] font-sans font-medium bg-muted text-muted-foreground" title="NOT NULL">
                            NN
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="py-2 pr-3 font-mono text-muted-foreground whitespace-nowrap">{col.type}</td>
                    <td className="py-2 pr-3 min-w-[160px] text-muted-foreground">
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
