'use client';

import { Database, HardDrive, Zap } from "lucide-react";
import { DbConnection } from "@/types";
import { useConnections } from "@/hooks/useConnections";

const ENGINE_META: Record<DbConnection["engine"], { icon: React.ElementType; color: string }> = {
  postgresql: { icon: Database,  color: "text-blue-600 dark:text-blue-400" },
  mysql:      { icon: Database,  color: "text-orange-600 dark:text-orange-400" },
  sqlite:     { icon: HardDrive, color: "text-green-600 dark:text-green-400" },
  redis:      { icon: Zap,       color: "text-red-600 dark:text-red-400" },
};

function StatusDot({ status }: { status: DbConnection["status"] }) {
  const map = {
    connected: "bg-green-500",
    connecting: "bg-yellow-500 animate-pulse",
    error: "bg-red-500",
  };
  return <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${map[status]}`} />;
}

interface ConnectionListProps {
  selectedId: number;
  onSelect: (id: number) => void;
}

/**
 * 连接选择器:横向紧凑 chip 行。
 * 替代原左侧竖列——单个连接时不再占半屏宽度/留大片空白;多连接自动换行。
 */
export function ConnectionList({ selectedId, onSelect }: ConnectionListProps) {
  const connections = useConnections((s) => s.connections);
  return (
    <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="数据源连接">
      {connections.map((conn) => {
        const meta = ENGINE_META[conn.engine];
        const Icon = meta.icon;
        const active = conn.id === selectedId;
        return (
          <button
            key={conn.id}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onSelect(conn.id)}
            title={`${conn.name} · ${conn.host !== "—" ? `${conn.host}:${conn.port}` : conn.database}`}
            className={`flex items-center gap-2 pl-3 pr-3.5 h-9 rounded-lg border text-left transition-colors cursor-pointer max-w-[280px] ${active ? "bg-blue-50 dark:bg-blue-950/40 border-blue-300 dark:border-blue-800" : "bg-card border-border hover:bg-muted"}`}
          >
            <Icon className={`w-4 h-4 shrink-0 ${meta.color}`} />
            <span className={`text-[13px] font-medium truncate ${active ? "text-blue-700 dark:text-blue-300" : "text-foreground"}`}>
              {conn.name}
            </span>
            <span className="hidden sm:inline text-[11px] text-muted-foreground font-mono truncate">
              {conn.host !== "—" ? `${conn.host}:${conn.port}` : conn.database}
            </span>
            <StatusDot status={conn.status} />
          </button>
        );
      })}
    </div>
  );
}
