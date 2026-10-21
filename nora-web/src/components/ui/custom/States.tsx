import React, { ReactNode } from "react";
import { Button } from "@/components/ui/button";

export function ResourceStatus({ loading, error, retry }: { loading: boolean; error: string | null; retry: () => Promise<void> }) {
  if (error) return <div role="alert" className="rounded-lg border border-destructive/30 p-3 text-xs space-y-2">
    <p className="text-destructive whitespace-pre-wrap break-words">{error}</p>
    <p className="text-muted-foreground">同步失败，当前内容可能不是最新结果。</p>
    <Button size="sm" variant="outline" disabled={loading} onClick={() => void retry()}>{loading ? "刷新中…" : "重试"}</Button>
  </div>;
  return loading ? <p role="status" className="text-xs text-muted-foreground py-3">加载中…</p> : null;
}

interface EmptyStateProps {
  icon: React.ElementType;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ icon: Icon, title, description, action, className = "" }: EmptyStateProps) {
  return (
    <div className={`px-5 py-12 sm:py-16 flex flex-col items-center justify-center text-center animate-in fade-in duration-150 ${className}`}>
      <div className="w-11 h-11 bg-muted/70 rounded-2xl flex items-center justify-center mb-4">
        <Icon className="w-5 h-5 text-muted-foreground" />
      </div>
      <h3 className="text-sm font-medium text-foreground">{title}</h3>
      {description && <p className="mt-2 text-[13px] leading-relaxed text-muted-foreground max-w-sm">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

