'use client';

import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { Header } from "@/components/layout/Header";
import { Database, Plus, Loader2, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConnectionList } from "@/components/data-sources/ConnectionList";
import { SchemaBrowser } from "@/components/data-sources/SchemaBrowser";
import { QueryConsole } from "@/components/data-sources/QueryConsole";
import { NewConnectionModal } from "@/components/data-sources/NewConnectionModal";
import { useConnections } from "@/hooks/useConnections";
import { useNotifications } from "@/hooks/useNotifications";

const VIEW_TABS = ["Schema 浏览", "查询控制台"] as const;

export default function DataSourcesPage() {
  const navigate = useNavigate();
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

  /** 让 AI 在对话页基于该连接生成 SQL(真实 agent,不是本地假生成) */
  const handleAskAi = () => {
    if (!selected) return;
    navigate(`/chat?prompt=${encodeURIComponent(`请基于数据源「${selected.name}」(${selected.database}) 帮我写一条查询:`)}`);
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

          <div className="flex flex-col md:flex-row gap-5 items-start animate-in fade-in slide-in-from-bottom-4 duration-500">
            <ConnectionList selectedId={selectedId ?? selected.id} onSelect={setSelectedId} />

            <div className="flex-1 min-w-0 w-full space-y-4">
              {/* 连接操作条:状态 + 真实操作(测试连接/删除) */}
              <ConnectionActionBar connectionId={selected.id} name={selected.name} />

              {/* View tabs */}
              <div className="flex items-center justify-between gap-3">
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
                <button
                  type="button"
                  onClick={handleAskAi}
                  className="text-[11px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer shrink-0"
                >
                  ✨ 让 AI 帮我写 SQL
                </button>
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

/** 连接操作条:显示真实状态,提供测试连接/删除(后端模式可用)。 */
function ConnectionActionBar({ connectionId, name }: { connectionId: number; name: string }) {
  const markStatus = useConnections((s) => s.markStatus);
  const removeConnection = useConnections((s) => s.removeConnection);
  const connections = useConnections((s) => s.connections);
  const conn = connections.find((c) => c.id === connectionId);
  const [testing, setTesting] = useState(false);

  const handleTest = async () => {
    setTesting(true);
    try {
      const { datasourcesApi } = await import("@/lib/services/datasourcesApi");
      const r = await datasourcesApi.testConnection(connectionId);
      markStatus(connectionId, r.ok ? "connected" : "error");
      toast(r.ok ? `连接正常${r.latencyMs != null ? ` · ${r.latencyMs}ms` : ""}` : `连接失败：${r.message}`);
    } catch (e) {
      markStatus(connectionId, "error");
      toast.error(`测试失败：${(e as Error).message}`);
    } finally {
      setTesting(false);
    }
  };

  const handleDelete = () => {
    if (!window.confirm(`确定删除连接「${name}」?该操作不可恢复。`)) return;
    removeConnection(connectionId);
    toast.success(`已删除「${name}」`);
  };

  return (
    <div className="bg-card border border-border rounded-xl px-3 py-2 flex items-center justify-end gap-2">
      <Button variant="outline" size="sm" className="h-7 text-[11px] px-2.5" onClick={handleTest} disabled={testing}>
        {testing ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : <RefreshCw className="w-3 h-3 mr-1" />}
        测试连接
      </Button>
      <Button
        variant="ghost"
        size="icon"
        className="w-7 h-7 text-muted-foreground hover:text-destructive"
        title="删除连接"
        onClick={handleDelete}
        disabled={!conn}
      >
        <Trash2 className="w-3.5 h-3.5" />
      </Button>
    </div>
  );
}
