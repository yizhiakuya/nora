'use client';

import { Folder, Database, Server, ArrowRight } from "lucide-react";
import { MOCK_GRAPH } from "@/lib/knowledgeData";

function NodeGroup({ icon: Icon, label, items, color }: {
  icon: React.ElementType; label: string; items: string[]; color: string;
}) {
  return (
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-1.5 mb-2">
        <Icon className={`w-3.5 h-3.5 ${color}`} />
        <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">{label}</span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {items.map((item) => (
          <span
            key={item}
            className="inline-flex items-center px-2 py-0.5 rounded-md text-[10px] font-medium bg-muted text-gray-700 dark:text-gray-300 border border-border"
          >
            {item}
          </span>
        ))}
      </div>
    </div>
  );
}

export function DataGraphView() {
  return (
    <div className="space-y-4">
      {MOCK_GRAPH.map((project) => (
        <div
          key={project.id}
          className="bg-card border border-border rounded-xl p-5"
        >
          <div className="flex items-center gap-2 mb-4">
            <Folder className="w-4 h-4 text-blue-600 dark:text-blue-400" />
            <span className="text-sm font-bold text-foreground">{project.name}</span>
            <span className="text-[10px] text-muted-foreground">
              {project.files.length} 文件 · {project.tables.length} 表 · {project.services.length} 服务
            </span>
          </div>

          <div className="flex flex-col md:flex-row gap-4 md:gap-2">
            <NodeGroup icon={Folder} label="文件" items={project.files} color="text-blue-600 dark:text-blue-400" />
            <ArrowRight className="hidden md:block w-4 h-4 text-muted-foreground/60 shrink-0 self-center" />
            <NodeGroup icon={Database} label="数据表" items={project.tables} color="text-orange-600 dark:text-orange-400" />
            <ArrowRight className="hidden md:block w-4 h-4 text-muted-foreground/60 shrink-0 self-center" />
            <NodeGroup icon={Server} label="服务" items={project.services} color="text-green-600 dark:text-green-400" />
          </div>
        </div>
      ))}
    </div>
  );
}
