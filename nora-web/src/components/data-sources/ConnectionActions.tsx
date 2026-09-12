'use client';

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { Loader2, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useConnections } from "@/hooks/useConnections";

interface ConnectionActionsProps {
  connectionId: number;
  name: string;
  database: string;
}

/**
 * 当前连接的操作组(并入视图工具行,替代原先几乎空白的独立操作条):
 * 测试连接(真实连通测试 + 状态回写) / 删除连接(二次确认) / 让 AI 写 SQL(跳转对话)。
 */
export function ConnectionActions({ connectionId, name, database }: ConnectionActionsProps) {
  const navigate = useNavigate();
  const markStatus = useConnections((s) => s.markStatus);
  const removeConnection = useConnections((s) => s.removeConnection);
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

  /** 让 AI 在对话页基于该连接生成 SQL(真实 agent,不是本地假生成) */
  const handleAskAi = () => {
    navigate(`/chat?prompt=${encodeURIComponent(`请基于数据源「${name}」(${database}) 帮我写一条查询:`)}`);
  };

  return (
    <div className="flex items-center gap-2 shrink-0">
      <button
        type="button"
        onClick={handleAskAi}
        className="text-xs text-blue-600 dark:text-blue-400 hover:underline cursor-pointer whitespace-nowrap"
      >
        ✨ 让 AI 帮我写 SQL
      </button>
      <Button variant="outline" size="sm" className="h-8 text-xs px-3" onClick={handleTest} disabled={testing}>
        {testing ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 mr-1" />}
        测试连接
      </Button>
      <Button
        variant="ghost"
        size="icon"
        className="w-8 h-8 text-muted-foreground hover:text-destructive"
        title="删除连接"
        onClick={handleDelete}
      >
        <Trash2 className="w-4 h-4" />
      </Button>
    </div>
  );
}
