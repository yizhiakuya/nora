import { AlertTriangle, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { reportToBackend } from "@/lib/errorReporter";

/**
 * 全局渲染错误边界(2026-09-17 接线,原为 Next.js 约定文件残留):
 * React 渲染树崩溃时兜底展示(白屏类灾难无法被 window.onerror 捕获,
 * 由 ErrorBoundary 的 componentDidCatch 接管),并把错误上报后端日志。
 *
 * 使用:包裹在各路由页面外层(见 App.tsx 的 RouteShell)。
 */
export function GlobalRouteError({
  error,
  resetErrorBoundary,
}: {
  error: unknown;
  resetErrorBoundary: () => void;
}) {
  const digest = error instanceof Error ? (error as Error & { digest?: string }).digest : undefined;
  return (
    <div className="flex-1 flex items-center justify-center p-8">
      <div className="max-w-md text-center">
        <div className="w-16 h-16 bg-red-50 dark:bg-red-950/40 border border-red-100 dark:border-red-900 rounded-2xl mx-auto flex items-center justify-center mb-4">
          <AlertTriangle className="w-8 h-8 text-red-400" />
        </div>
        <h2 className="text-lg font-bold text-foreground mb-2">页面出现了问题</h2>
        <p className="text-sm text-muted-foreground mb-1">渲染时发生错误，可以尝试重新加载此页面。</p>
        {digest && <p className="text-[10px] text-muted-foreground font-mono mb-4">错误码: {digest}</p>}
        <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={resetErrorBoundary}>
          <RefreshCw className="w-3.5 h-3.5 mr-1.5" /> 重试
        </Button>
      </div>
    </div>
  );
}

/** 渲染崩溃上报(react-error-boundary onError 回调用;上报自带 10s 去重)。 */
export function reportRenderError(error: unknown, info: { componentStack?: string | null }): void {
  const err = error instanceof Error ? error : new Error(String(error));
  reportToBackend({
    event: "react.render-error",
    message: err.message,
    stack: `${err.stack ?? ""}\n${info.componentStack ?? ""}`,
  });
}
