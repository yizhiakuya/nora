'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { McpManager } from "@/components/settings/McpSettings";
import { NewMcpServerModal } from "@/components/settings/NewMcpServerModal";
import { GitHubOAuthModal } from "@/components/settings/GitHubOAuthModal";
import { Button } from "@/components/ui/button";
import { LogIn, Plug, Plus } from "lucide-react";

/**
 * MCP 服务器独立管理页(侧边栏「MCP」):右上角「GitHub 登录」(OAuth 设备码,
 * 一键接入官方 GitHub MCP)+「注册服务器」→ 弹窗注册(对齐数据源/技能页的
 * 模态框模式)、测试连接、启停;已连接服务器的工具以 mcp__<server>__<tool>
 * 挂载给 Agent。
 */
export default function McpPage() {
  const [modalOpen, setModalOpen] = useState(false);
  const [githubOpen, setGithubOpen] = useState(false);
  // 注册/登录成功后 +1 触发列表重拉(轻量 key 模式,避免跨组件 ref)
  const [listVersion, setListVersion] = useState(0);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "MCP", isCurrent: true }]}
        actions={
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              className="h-8 text-xs"
              onClick={() => setGithubOpen(true)}
            >
              <LogIn className="w-3.5 h-3.5 mr-1.5" /> GitHub 登录
            </Button>
            <Button
              size="sm"
              className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
              onClick={() => setModalOpen(true)}
            >
              <Plus className="w-3.5 h-3.5 mr-1.5" /> 注册服务器
            </Button>
          </div>
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
                接入 MCP(Model Context Protocol)工具服务器——远程(HTTP/SSE)或本地进程(STDIO),
                扩展 Agent 的能力边界;工具按高风险管控,审批跟随当前权限档位。
                GitHub 可一键登录(OAuth),无需手动创建 token。
              </p>
            </div>
          </div>
          <McpManager listVersion={listVersion} />
        </div>
      </div>

      <NewMcpServerModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={() => setListVersion((v) => v + 1)}
      />
      <GitHubOAuthModal
        isOpen={githubOpen}
        onClose={() => setGithubOpen(false)}
        onLoggedIn={() => setListVersion((v) => v + 1)}
      />
    </>
  );
}
