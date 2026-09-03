import dynamic from "next/dynamic";

/**
 * react-markdown + remark-gfm 体积较大，通过动态导入拆分为独立 chunk，
 * 仅在页面真正渲染 Markdown 时加载（/chat 与 /agents 的 First Load JS 显著下降）。
 */
const MarkdownContent = dynamic(() => import("./MarkdownContent"));

export function Markdown({ children }: { children: string }) {
  return <MarkdownContent>{children}</MarkdownContent>;
}
