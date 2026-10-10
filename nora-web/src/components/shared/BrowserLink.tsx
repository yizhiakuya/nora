import { useContext, type ComponentPropsWithoutRef } from "react";
import { toast } from "sonner";
import { WorkspaceContext } from "@/components/layout/WorkspaceContext";
import { usePreferences } from "@/hooks/usePreferences";

/** 内容中的网页链接遵循打开偏好；文件链接先执行调用方的处理。 */
export function BrowserLink({ href, onClick, ...props }: ComponentPropsWithoutRef<"a">) {
  const workspace = useContext(WorkspaceContext);
  const mode = usePreferences(state => state.linkOpenMode);
  const web = !!href && /^(https?:)?\/\//i.test(href);
  return <a {...props} href={href} target={web ? "_blank" : props.target} rel={web ? "noopener noreferrer" : props.rel}
    onClick={event => {
      onClick?.(event);
      if (!web || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.currentTarget.hasAttribute("download") || mode === "external") return;
      event.preventDefault();
      if (!workspace?.sessionId) { toast.error("请先选择或新建对话，再使用内置浏览器"); return; }
      workspace.browser.openLink(event.currentTarget.href);
    }} />;
}
