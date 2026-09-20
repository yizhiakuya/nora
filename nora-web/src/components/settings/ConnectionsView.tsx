'use client';

import { useState } from "react";
import { LogIn, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { McpManager } from "@/components/settings/McpSettings";
import { NewMcpServerModal } from "@/components/settings/NewMcpServerModal";
import { GitHubOAuthModal } from "@/components/settings/GitHubOAuthModal";

/**
 * 「连接与工具」设置分组(M1-04,2026-09-20,方案 §4.4):
 * MCP 服务器管理(注册/启停/测试/工具清单/GitHub 一键登录)。
 * 数据源连接与手机相册等来源在各自入口管理,这里聚焦工具型连接。
 */
export function ConnectionsView() {
  const [modalOpen, setModalOpen] = useState(false);
  const [githubOpen, setGithubOpen] = useState(false);
  const [listVersion, setListVersion] = useState(0);

  return (
    <>
      <div className="flex items-center justify-between mb-4">
        <p className="text-xs text-muted-foreground max-w-[60%]">
          接入 MCP 工具服务器——远程(HTTP/SSE)或本地进程(STDIO)。工具调用按风险分级，
          审批跟随当前权限档位；GitHub 可一键登录（OAuth）。
        </p>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" className="h-8 text-xs" onClick={() => setGithubOpen(true)}>
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
      </div>
      <McpManager listVersion={listVersion} />
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
