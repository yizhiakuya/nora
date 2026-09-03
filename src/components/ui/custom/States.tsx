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
      <div className="w-16 h-16 bg-gray-50 dark:bg-gray-900 rounded-full flex items-center justify-center mb-4 border border-gray-100 dark:border-gray-800 shadow-sm">
        <Icon className="w-8 h-8 text-gray-300 dark:text-gray-600" />
      </div>
      <h3 className="text-sm font-bold text-gray-700 dark:text-gray-200 mb-1">{title}</h3>
      {description && <p className="text-xs text-gray-500 dark:text-gray-400 max-w-sm mb-4">{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
}

