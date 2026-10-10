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
    <div className={`px-5 py-16 flex flex-col items-center justify-center text-center animate-in fade-in ${className}`}>
      <div className="w-12 h-12 bg-muted rounded-xl flex items-center justify-center mb-4">
        <Icon className="w-6 h-6 text-muted-foreground" />
      </div>
      <h3 className="text-sm font-semibold text-foreground mb-1.5">{title}</h3>
      {description && <p className="text-sm leading-relaxed text-muted-foreground max-w-sm mb-5">{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
}

