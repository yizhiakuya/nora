import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import { useEffect } from "react";
import { Sidebar } from "@/components/layout/Sidebar";
import { ServiceUnavailablePage } from "@/components/layout/ServiceUnavailablePage";
import { useBackendHealth } from "@/hooks/useBackendHealth";

import HomePage from "@/app/page";
import FilesPage from "@/app/files/page";
import ChatPage from "@/app/chat/page";
import KnowledgePage from "@/app/knowledge/page";
import SkillsPage from "@/app/skills/page";
import DataSourcesPage from "@/app/data-sources/page";
import EnvironmentsPage from "@/app/environments/page";
import AutomationsPage from "@/app/automations/page";
import SettingsPage from "@/app/settings/page";

const TITLES: Record<string, string> = {
  "/": "首页",
  "/files": "文件",
  "/chat": "对话",
  "/knowledge": "知识库",
  "/skills": "AI 能力",
  "/data-sources": "数据源",
  "/environments": "环境控制台",
  "/automations": "自动任务",
  "/settings": "设置中心",
};

function RouteShell({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const startPolling = useBackendHealth((s) => s.startPolling);
  const online = useBackendHealth((s) => s.online);
  // 全局后端健康探测:离线时所有路由替换为服务不可用页
  useEffect(() => {
    startPolling();
  }, [startPolling]);
  useEffect(() => {
    const title = TITLES[location.pathname];
    document.title = title ? `${title} · Nora 个人工作台` : "Nora 个人工作台";
  }, [location.pathname]);
  // API 不在线 = 全部功能不可用:整屏替换为服务不可用页(无侧栏/导航)
  if (online === false) {
    return <ServiceUnavailablePage />;
  }
  return (
    <div className="h-screen flex overflow-hidden text-gray-800 dark:text-gray-100 bg-background dark:bg-background">
      <Sidebar />
      <main className="flex-1 flex flex-col overflow-hidden bg-background relative">
        {children}
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<RouteShell><HomePage /></RouteShell>} />
      <Route path="/files" element={<RouteShell><FilesPage /></RouteShell>} />
      <Route path="/chat" element={<RouteShell><ChatPage /></RouteShell>} />
      <Route path="/knowledge" element={<RouteShell><KnowledgePage /></RouteShell>} />
      <Route path="/skills" element={<RouteShell><SkillsPage /></RouteShell>} />
      <Route path="/data-sources" element={<RouteShell><DataSourcesPage /></RouteShell>} />
      <Route path="/environments" element={<RouteShell><EnvironmentsPage /></RouteShell>} />
      <Route path="/automations" element={<RouteShell><AutomationsPage /></RouteShell>} />
      <Route path="/settings" element={<RouteShell><SettingsPage /></RouteShell>} />
      {/* 兼容旧路由 → 重定向 */}
      <Route path="/models" element={<Navigate to="/settings?tab=模型管理" replace />} />
      <Route path="/env-vars" element={<Navigate to="/settings?tab=环境变量" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
