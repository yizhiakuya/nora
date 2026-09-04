'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Database, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConnectionList } from "@/components/data-sources/ConnectionList";
import { SchemaBrowser } from "@/components/data-sources/SchemaBrowser";
import { QueryConsole } from "@/components/data-sources/QueryConsole";
import { NewConnectionModal } from "@/components/data-sources/NewConnectionModal";
import { useConnections } from "@/hooks/useConnections";
import { useNotifications } from "@/hooks/useNotifications";

const VIEW_TABS = ["Schema 浏览", "查询控制台"] as const;

export default function DataSourcesPage() {
  const [selectedId, setSelectedId] = useState<number>(() => useConnections.getState().connections[0].id);
  const [activeView, setActiveView] = useState<(typeof VIEW_TABS)[number]>("Schema 浏览");
  const [targetSql, setTargetSql] = useState("");
  const [modalOpen, setModalOpen] = useState(false);
  const connections = useConnections((s) => s.connections);
  const addNotification = useNotifications((s) => s.addNotification);

  const selected = connections.find((c) => c.id === selectedId) ?? connections[0];

  const handleQueryTable = (tableName: string) => {
    setTargetSql(`SELECT * FROM ${tableName} LIMIT 20;`);
    setActiveView("查询控制台");
  };

  const handleCreated = (id: number) => {
    setSelectedId(id);
    const conn = useConnections.getState().connections.find((c) => c.id === id);
    if (conn) addNotification("数据源已连接", `「${conn.name}」（${conn.engine}）连接成功，AI 可读取其 Schema 辅助生成 SQL。`);
  };

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "数据源", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => setModalOpen(true)}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建连接
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Database className="w-5 h-5 text-purple-600 dark:text-purple-400" /> 数据源
            </h1>
            <p className="text-xs text-muted-foreground mt-1">
              连接开发/测试数据库，浏览 Schema，执行查询——AI 可读取表结构辅助生成 SQL。
            </p>
          </div>

          <div className="flex flex-col md:flex-row gap-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <ConnectionList selectedId={selectedId} onSelect={setSelectedId} />

            <div className="flex-1 min-w-0 space-y-4">
              {/* Connection info bar */}
              <div className="bg-card border border-border rounded-xl px-4 py-3 flex items-center justify-between">
                <div>
                  <div className="text-sm font-bold text-foreground">{selected.name}</div>
                  <div className="text-[10px] text-muted-foreground font-mono mt-0.5">
                    {selected.host !== "—"
                      ? `${selected.engine}://${selected.host}:${selected.port}/${selected.database}`
                      : selected.database}
                  </div>
                </div>
                <div className="flex items-center gap-3 text-[10px] text-muted-foreground">
                  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-medium border ${selected.status === "connected" ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800" : "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800"}`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${selected.status === "connected" ? "bg-green-500 animate-pulse" : "bg-red-500"}`} />
                    {selected.status === "connected" ? "已连接" : "连接失败"}
                  </span>
                  <span className="tabular-nums">{selected.activeConn}/{selected.maxConn} conn</span>
                </div>
              </div>

              {/* View tabs */}
              <div className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit" role="tablist" aria-label="数据源视图">
                {VIEW_TABS.map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    role="tab"
                    aria-selected={activeView === tab}
                    onClick={() => setActiveView(tab)}
                    className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${activeView === tab ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                  >
                    {tab}
                  </button>
                ))}
              </div>

              {activeView === "Schema 浏览" && (
                <SchemaBrowser database={selected.database} onQueryTable={handleQueryTable} />
              )}
              {activeView === "查询控制台" && (
                <QueryConsole database={selected.database} initialSql={targetSql} />
              )}
            </div>
          </div>
        </div>
      </div>

      <NewConnectionModal isOpen={modalOpen} onClose={() => setModalOpen(false)} onCreated={handleCreated} />
    </>
  );
}
