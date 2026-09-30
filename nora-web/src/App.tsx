import { Routes, Route, Navigate, useLocation, useSearchParams } from "react-router-dom";
import { lazy, Suspense, useEffect } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { Sidebar } from "@/components/layout/Sidebar";
import { FileViewerWorkspace } from "@/components/layout/FileViewerWorkspace";
import { useFileViewer } from "@/hooks/useFileViewer";
import { ServiceUnavailablePage } from "@/components/layout/ServiceUnavailablePage";
import { NotificationWatcher } from "@/components/layout/NotificationWatcher";
import { useCommandPalette } from "@/hooks/useCommandPalette";
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
const TasksPage = lazy(() => import("@/app/tasks/page"));
const SettingsPage = lazy(() => import("@/app/settings/page"));
const LoginPage = lazy(() => import("@/app/login/page"));
// 全局搜索面板只在打开时加载(主入口包体门禁:静态引入会进主包)
const CommandPalette = lazy(() => import("@/components/home/CommandPalette").then(m => ({ default: m.CommandPalette })));

const TITLES: Record<string, string> = {
  "/": "助手",
  "/files": "资料",
  "/chat": "助手",
  "/tasks": "任务",
  "/knowledge": "资料",
  "/skills": "技能",
  "/data-sources": "数据源",
  "/environments": "环境控制台",
  "/automations": "任务",
  "/settings": "设置",
  "/login": "登录",
};

function RouteShell({ children }: { children: React.ReactNode }) {
  const viewerOpen = useFileViewer(state => state.isOpen);
  const location = useLocation();
  const startPolling = useBackendHealth((s) => s.startPolling);
  const online = useBackendHealth((s) => s.online);
  // 全局搜索快捷键(§7 评审:此前 Cmd+K 注册在首页组件里,离开首页后
  // 按同一方式打不开「全局搜索」)。提到 RouteShell——任何页面都可呼出;
  // 开关走 useCommandPalette 共享 store(首页搜索框点击也打开同一实例)。
  const isCmdKOpen = useCommandPalette((s) => s.isOpen);
  const toggleCmdK = useCommandPalette((s) => s.toggle);
  const closeCmdK = useCommandPalette((s) => s.close);
  // 全局后端健康探测:离线时所有路由替换为服务不可用页
  useEffect(() => {
    startPolling();
  }, [startPolling]);
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        toggleCmdK();
      }
      if (e.key === "Escape") closeCmdK();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [toggleCmdK, closeCmdK]);
  useEffect(() => {
    const title = TITLES[location.pathname];
    document.title = title ? `${title} · Nora` : "Nora";
  }, [location.pathname]);
  // API 不在线 = 全部功能不可用:整屏替换为服务不可用页(无侧栏/导航)
  if (online === false) {
    return <ServiceUnavailablePage />;
  }
  return (
    <div className="h-screen flex overflow-hidden text-gray-800 dark:text-gray-100 bg-background dark:bg-background">
      {/* 全局后台事件观察器:自动任务执行/PROC 守护事件在任何页面都能进通知中心 */}
      <NotificationWatcher />
      {/* 全局搜索(Cmd+K):任何页面可呼出;首页搜索框复用同一实例。
          懒加载 + 仅打开时渲染(面板关闭时不挂载,避免无谓 chunk 拉取) */}
      {isCmdKOpen && (
        <Suspense fallback={null}>
          <CommandPalette isOpen onClose={closeCmdK} />
        </Suspense>
      )}
      <Sidebar compact={viewerOpen} />
      <FileViewerWorkspace>{children}</FileViewerWorkspace>
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
      {/* 任务聚合页(M1):view=running|schedules|history */}
      <Route path="/tasks" element={<Page><TasksPage /></Page>} />
      {/* 兼容旧路由(产品改造方案 §3.2):/automations → /tasks(view 保留) */}
      <Route path="/automations" element={<CompatAutomations />} />
      <Route path="/knowledge" element={<Page><KnowledgePage /></Page>} />
      <Route path="/skills" element={<Page><SkillsPage /></Page>} />
      <Route path="/mcp" element={<Page><McpPage /></Page>} />
      <Route path="/data-sources" element={<Page><DataSourcesPage /></Page>} />
      <Route path="/environments" element={<Page><EnvironmentsPage /></Page>} />
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

/**
 * /automations 兼容跳转(M1):保留可识别的历史子视图——
 * ?tab=执行历史 → /tasks?view=history;其余 → /tasks?view=schedules。
 * 旧链接(含侧栏书签)不丢目标。
 */
function CompatAutomations() {
  const [params] = useSearchParams();
  const tab = params.get("tab");
  const view = tab === "执行历史" ? "history" : "schedules";
  return <Navigate to={`/tasks?view=${view}`} replace />;
}
