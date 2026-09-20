import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import { lazy, Suspense, useEffect } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { Sidebar } from "@/components/layout/Sidebar";
import { ServiceUnavailablePage } from "@/components/layout/ServiceUnavailablePage";
import { NotificationWatcher } from "@/components/layout/NotificationWatcher";
import { useBackendHealth } from "@/hooks/useBackendHealth";
import { installGlobalErrorReporting } from "@/lib/errorReporter";
import { GlobalRouteError, reportRenderError } from "@/app/error";

// 全局错误兜底(window.onerror/unhandledrejection/资源加载失败)→ 上报后端日志
installGlobalErrorReporting();

// 路由级懒加载(2026-09-20,审查报告 P2 工程门禁):此前 11 个页面全部静态导入,
// 主包 814KB/gzip 240KB(目标 ≤180KB)——首屏必须下载所有页面代码。
// 懒加载后每个页面独立 chunk,首屏只含首页 + 公共依赖;重型页面
// (文件预览/媒体/对话)按访问时机加载。
const HomePage = lazy(() => import("@/app/page"));
const FilesPage = lazy(() => import("@/app/files/page"));
const ChatPage = lazy(() => import("@/app/chat/page"));
const KnowledgePage = lazy(() => import("@/app/knowledge/page"));
const SkillsPage = lazy(() => import("@/app/skills/page"));
const McpPage = lazy(() => import("@/app/mcp/page"));
const DataSourcesPage = lazy(() => import("@/app/data-sources/page"));
const EnvironmentsPage = lazy(() => import("@/app/environments/page"));
const AutomationsPage = lazy(() => import("@/app/automations/page"));
const SettingsPage = lazy(() => import("@/app/settings/page"));
const LoginPage = lazy(() => import("@/app/login/page"));

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
  "/login": "登录",
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
      {/* 全局后台事件观察器:自动任务执行/PROC 守护事件在任何页面都能进通知中心 */}
      <NotificationWatcher />
      <Sidebar />
      <main className="flex-1 flex flex-col overflow-hidden bg-background relative">
        {children}
      </main>
    </div>
  );
}

/**
 * 页面级包裹(2026-09-17):RouteShell(健康探测/侧栏/标题)+ 渲染错误边界
 * ——渲染崩溃时展示 GlobalRouteError 兜底并上报(白屏类灾难的最后防线)。
 * Suspense(2026-09-20):懒加载 chunk 拉取期间显示轻量占位(避免白屏闪烁)。
 */
function Page({ children }: { children: React.ReactNode }) {
  return (
    <RouteShell>
      <ErrorBoundary
        FallbackComponent={GlobalRouteError}
        onError={reportRenderError}
        onReset={() => window.location.reload()}
      >
        <Suspense fallback={<RouteLoading />}>{children}</Suspense>
      </ErrorBoundary>
    </RouteShell>
  );
}

/** 懒加载占位:与页面背景一致的轻量骨架,避免切换时的白屏。 */
function RouteLoading() {
  return (
    <div className="flex-1 flex items-center justify-center bg-background">
      <div className="text-xs text-muted-foreground">加载中…</div>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Page><HomePage /></Page>} />
      <Route path="/files" element={<Page><FilesPage /></Page>} />
      <Route path="/chat" element={<Page><ChatPage /></Page>} />
      <Route path="/knowledge" element={<Page><KnowledgePage /></Page>} />
      <Route path="/skills" element={<Page><SkillsPage /></Page>} />
      <Route path="/mcp" element={<Page><McpPage /></Page>} />
      <Route path="/data-sources" element={<Page><DataSourcesPage /></Page>} />
      <Route path="/environments" element={<Page><EnvironmentsPage /></Page>} />
      <Route path="/automations" element={<Page><AutomationsPage /></Page>} />
      <Route path="/settings" element={<Page><SettingsPage /></Page>} />
      {/* 令牌登录页(2026-09-19):独立全屏,无侧栏/无健康探测壳 */}
      <Route path="/login" element={
        <Suspense fallback={<RouteLoading />}>
          <LoginPage />
        </Suspense>
      } />
      {/* 兼容旧路由 → 重定向 */}
      <Route path="/models" element={<Navigate to="/settings?tab=模型管理" replace />} />
      <Route path="/env-vars" element={<Navigate to="/settings?tab=环境变量" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
