import React from "react";

/**
 * 页面容器原语(2026-09-17 响应式系统化)。
 *
 * 统一此前散落各页的 `flex-1 overflow-y-auto p-4 sm:p-6` + `max-w-6xl mx-auto`
 * 写法(9 个页面 9 套拷贝)。收口后:
 * - 页面留白/最大宽度/滚动行为一处定义,全局一致;
 * - 需要更宽内容(如文件中心网格)用 size="wide"。
 */
export function PageContainer({
  children,
  size = "default",
  className = "",
}: {
  children: React.ReactNode;
  /** default=6xl(常规页面);wide=7xl(密集内容);full=不限宽 */
  size?: "default" | "wide" | "full";
  className?: string;
}) {
  const maxW = size === "wide" ? "max-w-7xl" : size === "full" ? "" : "max-w-6xl";
  return (
    <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background relative">
      <div className={`${maxW} mx-auto pb-24 ${className}`}>{children}</div>
    </div>
  );
}
