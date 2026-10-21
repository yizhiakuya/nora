import { lazy, Suspense } from "react";

/**
 * react-markdown + remark-gfm 体积较大，通过动态导入拆分为独立 chunk，
 * 仅在页面真正渲染 Markdown 时加载（降低 /chat 首屏包体积）。
 */
const MarkdownContent = lazy(() => import("./MarkdownContent"));

export function Markdown({ children, className, fileTarget, sessionId }: { children: string; className?: string; fileTarget?: string; sessionId?: string }) {
  return <Suspense fallback={null}><MarkdownContent className={className} fileTarget={fileTarget} sessionId={sessionId}>{children}</MarkdownContent></Suspense>;
}
