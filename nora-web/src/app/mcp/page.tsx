'use client';

import { Header } from "@/components/layout/Header";
import { Plug } from "lucide-react";
import { ConnectionsView } from "@/components/settings/ConnectionsView";

/**
 * /mcp 兼容页(M1-04,2026-09-20):MCP 管理现位于
 * /settings?section=connections;此路由保留直达(书签/深链不丢目标)。
 */
export default function McpPage() {
  return (
    <>
      <Header
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "设置", href: "/settings", isCurrent: false }, { label: "连接与工具", isCurrent: true }]}
      />
      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20 animate-in fade-in slide-in-from-bottom-4 duration-300">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <Plug className="w-5 h-5 text-blue-500 dark:text-blue-400" /> 连接与工具
          </h1>
          <ConnectionsView />
        </div>
      </div>
    </>
  );
}

