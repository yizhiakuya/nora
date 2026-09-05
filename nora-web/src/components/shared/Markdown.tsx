import dynamic from "next/dynamic";

/**
 * react-markdown + remark-gfm 体积较大，通过动态导入拆分为独立 chunk，
 * 仅在页面真正渲染 Markdown 时加载（降低 /chat 首屏包体积）。
 */
const MarkdownContent = dynamic(() => import("./MarkdownContent"));

export function Markdown({ children, className }: { children: string; className?: string }) {
  return <MarkdownContent className={className}>{children}</MarkdownContent>;
}
