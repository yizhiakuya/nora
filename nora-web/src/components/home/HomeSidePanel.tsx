'use client';

import { useNavigate } from "react-router-dom";
import { Database, Server, BookOpen } from "lucide-react";
import { useConnections } from "@/hooks/useConnections";
import { useServices } from "@/hooks/useServices";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { MOCK_INDEX_STATS } from "@/lib/knowledgeData";

export function HomeSidePanel() {
  const navigate = useNavigate();
  const connections = useConnections((s) => s.connections);
  const services = useServices((s) => s.services);
  const docs = useKnowledgeDocs((s) => s.docs);

  const connectedDb = connections.filter((c) => c.status === "connected").length;
  const runningSvcs = services.filter((s) => s.status === "running").length;
  const totalChunks = docs.reduce((acc, d) => acc + (d.chunks || 0), 0);
  const indexedDocsCount = docs.filter((d) => d.status === "indexed").length;

  return (
    <div className="w-full lg:w-[35%] flex flex-col space-y-4">
      {/* 数据源状态 */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-foreground flex items-center gap-2">
          <Database className="w-4 h-4 text-purple-500 dark:text-purple-400" /> 数据源
        </h2>
        <button onClick={() => navigate("/data-sources")} className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer">
          管理 →
        </button>
      </div>
      <div className="bg-card border border-border rounded-xl shadow-sm p-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-2xl font-bold text-foreground tabular-nums">{connectedDb}<span className="text-sm text-muted-foreground font-normal">/{connections.length}</span></div>
            <div className="text-[10px] text-muted-foreground mt-0.5">已连接数据源</div>
          </div>
          <div className="flex flex-col items-end gap-0.5">
            {connections.slice(0, 3).map((c) => (
              <span key={c.id} className="text-[10px] text-muted-foreground flex items-center gap-1">
                <span className={`w-1.5 h-1.5 rounded-full ${c.status === "connected" ? "bg-green-500" : "bg-red-500"}`} />
                {c.name}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* 环境状态 */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-foreground flex items-center gap-2">
          <Server className="w-4 h-4 text-green-500 dark:text-green-400" /> 环境
        </h2>
        <button onClick={() => navigate("/environments")} className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer">
          控制台 →
        </button>
      </div>
      <div className="bg-card border border-border rounded-xl shadow-sm p-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-2xl font-bold text-foreground tabular-nums">{runningSvcs}<span className="text-sm text-muted-foreground font-normal">/{services.length}</span></div>
            <div className="text-[10px] text-muted-foreground mt-0.5">服务运行中</div>
          </div>
          <div className="flex flex-col items-end gap-0.5">
            {services.map((s) => (
              <span key={s.id} className="text-[10px] text-muted-foreground flex items-center gap-1">
                <span className={`w-1.5 h-1.5 rounded-full ${s.status === "running" ? "bg-green-500" : "bg-gray-300 dark:bg-gray-600"}`} />
                {s.name}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* 知识库状态 */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-foreground flex items-center gap-2">
          <BookOpen className="w-4 h-4 text-blue-500 dark:text-blue-400" /> 知识库
        </h2>
        <button onClick={() => navigate("/knowledge")} className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer">
          管理 →
        </button>
      </div>
      <div className="bg-card border border-border rounded-xl shadow-sm p-4">
        <div className="text-2xl font-bold text-foreground tabular-nums">{totalChunks.toLocaleString()} <span className="text-sm text-muted-foreground font-normal">chunks</span></div>
        <div className="text-[10px] text-muted-foreground mt-0.5">{indexedDocsCount} 个文档已索引 · {MOCK_INDEX_STATS.model}</div>
      </div>
    </div>
  );
}
