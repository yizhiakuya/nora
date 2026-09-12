'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Database, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConnectionList } from "@/components/data-sources/ConnectionList";
import { ConnectionActions } from "@/components/data-sources/ConnectionActions";
import { SchemaBrowser } from "@/components/data-sources/SchemaBrowser";
import { QueryConsole } from "@/components/data-sources/QueryConsole";
import { NewConnectionModal } from "@/components/data-sources/NewConnectionModal";
import { useConnections } from "@/hooks/useConnections";
import { useNotifications } from "@/hooks/useNotifications";

const VIEW_TABS = ["Schema 浏览", "查询控制台"] as const;

export default function DataSourcesPage() {
  // 连接列表可能为空(后端不可达/首次使用):延迟取首项,避免 undefined.id 崩页
  const [selectedId, setSelectedId] = useState<number | null>(() => useConnections.getState().connections[0]?.id ?? null);
  const [activeView, setActiveView] = useState<(typeof VIEW_TABS)[number]>("Schema 浏览");
  const [targetSql, setTargetSql] = useState("");
  const [modalOpen, setModalOpen] = useState(false);
  const connections = useConnections((s) => s.connections);
  const syncFromBackend = useConnections((s) => s.syncFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);

  // 后端模式:进入页面拉一次服务端连接列表
  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

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

  // 空列表:引导新建连接,而不是渲染 undefined
  if (!selected) {
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
        <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-background">
          <div className="max-w-6xl mx-auto pt-24 flex flex-col items-center gap-3 text-muted-foreground">
            <Database className="w-8 h-8 opacity-20" />
            <p className="text-sm">暂无数据源连接</p>
            <p className="text-xs">点击右上角「新建连接」添加第一个数据库</p>
          </div>
          <NewConnectionModal isOpen={modalOpen} onClose={() => setModalOpen(false)} onCreated={handleCreated} />
        </div>
      </>
    );
  }

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
        <div className="max-w-6xl mx-auto space-y-5 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Database className="w-5 h-5 text-purple-600 dark:text-purple-400" /> 数据源
            </h1>
            <p className="text-xs text-muted-foreground mt-1">
              连接开发/测试数据库，浏览 Schema，执行查询——AI 可读取表结构辅助生成 SQL。
            </p>
          </div>

          <div className="space-y-4 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {/* 连接选择条:横向 chip 行,选中即切换当前连接 */}
            <ConnectionList selectedId={selectedId ?? selected.id} onSelect={setSelectedId} />

            <div className="min-w-0 w-full space-y-4">
              {/* 工具行:视图切换 + 当前连接操作(测试/删除/AI 写 SQL) */}
              <div className="flex items-center justify-between gap-3 flex-wrap">
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
                <ConnectionActions connectionId={selected.id} name={selected.name} database={selected.database} />
              </div>

              {activeView === "Schema 浏览" && (
                <SchemaBrowser database={selected.database} connectionId={selected.id} onQueryTable={handleQueryTable} />
              )}
              {activeView === "查询控制台" && (
                <QueryConsole database={selected.database} connectionId={selected.id} initialSql={targetSql} />
              )}
            </div>
          </div>
        </div>
      </div>

      <NewConnectionModal isOpen={modalOpen} onClose={() => setModalOpen(false)} onCreated={handleCreated} />
    </>
  );
}
