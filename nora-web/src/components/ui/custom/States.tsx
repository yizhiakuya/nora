import React, { ReactNode } from "react";

interface EmptyStateProps {
  icon: React.ElementType;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ icon: Icon, title, description, action, className = "" }: EmptyStateProps) {
  return (
    <div className={`py-20 flex flex-col items-center justify-center text-center animate-in fade-in ${className}`}>
      <div className="w-16 h-16 bg-muted rounded-full flex items-center justify-center mb-4 border border-border shadow-sm">
        <Icon className="w-8 h-8 text-muted-foreground/60" />
      </div>
      <h3 className="text-sm font-bold text-foreground mb-1">{title}</h3>
      {description && <p className="text-xs text-muted-foreground max-w-sm mb-4">{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
}

