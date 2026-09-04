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
    connected: "bg-green-500 animate-pulse",
    connecting: "bg-yellow-500 animate-pulse",
    error: "bg-red-500",
  };
  return <span className={`w-2 h-2 rounded-full shrink-0 ${map[status]}`} />;
}

interface ConnectionListProps {
  selectedId: number;
  onSelect: (id: number) => void;
}

export function ConnectionList({ selectedId, onSelect }: ConnectionListProps) {
  const connections = useConnections((s) => s.connections);
  return (
    <div className="w-full md:w-56 shrink-0 space-y-1.5">
      {connections.map((conn) => {
        const meta = ENGINE_META[conn.engine];
        const Icon = meta.icon;
        const active = conn.id === selectedId;
        return (
          <button
            key={conn.id}
            type="button"
            onClick={() => onSelect(conn.id)}
            className={`w-full flex items-center gap-2.5 px-3 py-2.5 rounded-lg text-left transition-colors cursor-pointer ${active ? "bg-blue-50 dark:bg-blue-950/40 border border-blue-200 dark:border-blue-800" : "bg-card border border-border hover:bg-muted"}`}
          >
            <Icon className={`w-4 h-4 shrink-0 ${meta.color}`} />
            <div className="min-w-0 flex-1">
              <div className={`text-xs font-medium truncate ${active ? "text-blue-700 dark:text-blue-300" : "text-foreground"}`}>
                {conn.name}
              </div>
              <div className="text-[10px] text-muted-foreground font-mono truncate">
                {conn.host !== "—" ? `${conn.host}:${conn.port}` : conn.database}
              </div>
            </div>
            <StatusDot status={conn.status} />
          </button>
        );
      })}
    </div>
  );
}
