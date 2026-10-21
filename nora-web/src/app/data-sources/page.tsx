'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Database, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ResourceStatus } from "@/components/ui/custom/States";
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
  const { loading, syncError } = useConnections();
  const syncFromBackend = useConnections((s) => s.syncFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);

  // 后端模式:进入页面拉一次服务端连接列表
  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

  const selected = connections.find((c) => c.id === selectedId) ?? connections[0];

  /** 表→控制台:JDBC 传表名(包 SELECT);Redis 传完整命令(后端已生成) */
  const handleQueryTable = (tableNameOrCommand: string) => {
    setTargetSql(selected?.engine === "redis"
      ? tableNameOrCommand
      : `SELECT * FROM ${tableNameOrCommand} LIMIT 20;`);
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
          breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "数据源", isCurrent: true }]}
          actions={
            <Button size="sm" onClick={() => setModalOpen(true)}>
              <Plus className="w-4 h-4" /> 新建连接
            </Button>
          }
        />
        <div className="page-content custom-scroll">
          <div className="page-container">
            <div>
              <h1 className="page-heading">数据源</h1>
              <p className="page-description">连接数据库，浏览表结构，执行查询。</p>
            </div>
            <ResourceStatus loading={loading} error={syncError} retry={syncFromBackend} />
            {!loading && !syncError && <EmptyState icon={Database} title="暂无数据源连接" description="添加第一个数据库后，即可浏览表结构或运行查询。" />}
          </div>
          <NewConnectionModal isOpen={modalOpen} onClose={() => setModalOpen(false)} onCreated={handleCreated} />
        </div>
      </>
    );
  }

  return (
    <>
      <Header
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "数据源", isCurrent: true }]}
        actions={
          <Button size="sm" onClick={() => setModalOpen(true)}>
            <Plus className="w-4 h-4" /> 新建连接
          </Button>
        }
      />

      <div className="page-content custom-scroll">
        <div className="page-container">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="page-heading flex items-center gap-2.5">
              <Database className="w-5 h-5 text-muted-foreground" /> 数据源
            </h1>
            <p className="page-description">
              连接数据库与 Redis，浏览结构，执行查询、写入和结构变更。
            </p>
          </div>

          <div className="space-y-4 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <ResourceStatus loading={loading} error={syncError} retry={syncFromBackend} />
            {/* 连接选择条:横向 chip 行,选中即切换当前连接 */}
            <ConnectionList selectedId={selectedId ?? selected.id} onSelect={setSelectedId} />

            <Tabs value={activeView} onValueChange={(value) => setActiveView(value as (typeof VIEW_TABS)[number])} className="min-w-0 w-full space-y-4">
              {/* 工具行:视图切换 + 当前连接操作(测试/删除/AI 写 SQL) */}
              <div className="flex items-center justify-between gap-3 flex-wrap">
                <TabsList aria-label="数据源视图">
                  {VIEW_TABS.map((view) => <TabsTrigger key={view} value={view}>{view}</TabsTrigger>)}
                </TabsList>
                <ConnectionActions connectionId={selected.id} name={selected.name} database={selected.database} />
              </div>

              <TabsContent value="Schema 浏览">
                <SchemaBrowser key={selected.id} database={selected.database} connectionId={selected.id} engine={selected.engine} onQueryTable={handleQueryTable} />
              </TabsContent>
              <TabsContent value="查询控制台">
                <QueryConsole database={selected.database} connectionId={selected.id} engine={selected.engine} initialSql={targetSql} />
              </TabsContent>
            </Tabs>
          </div>
        </div>
      </div>

      <NewConnectionModal isOpen={modalOpen} onClose={() => setModalOpen(false)} onCreated={handleCreated} />
    </>
  );
}
