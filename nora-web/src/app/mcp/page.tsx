'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { McpManager } from "@/components/settings/McpSettings";
import { Button } from "@/components/ui/button";
import { Plug, Plus } from "lucide-react";

/**
 * MCP 服务器独立管理页(侧边栏「MCP」):注册远程 MCP 工具服务器、
 * 测试连接、启停;已连接服务器的工具以 mcp__<server>__<tool> 挂载给 Agent。
 * 布局对齐 AI 能力页:页头右侧主操作按钮 + 宽版内容区。
 */
export default function McpPage() {
  const [formOpen, setFormOpen] = useState(false);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "MCP", isCurrent: true }]}
        actions={
          <Button
            size="sm"
            className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
            onClick={() => setFormOpen(true)}
          >
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 注册服务器
          </Button>
        }
      />
      <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20 animate-in fade-in slide-in-from-bottom-4 duration-300">
          <div className="flex flex-col mb-6 space-y-4">
            <div>
              <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
                <Plug className="w-5 h-5 text-blue-500 dark:text-blue-400" /> MCP 服务器
              </h1>
              <p className="text-xs text-muted-foreground mt-1">
                接入远程 MCP(Model Context Protocol)工具服务器,扩展 Agent 的能力边界;工具按高风险管控,执行前需确认。
              </p>
            </div>
          </div>
          <McpManager formOpen={formOpen} onFormToggle={setFormOpen} />
        </div>
      </div>
    </>
  );
}
