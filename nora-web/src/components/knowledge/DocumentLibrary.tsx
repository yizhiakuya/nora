'use client';

import { useMemo } from "react";
import { Search } from "lucide-react";
import { SOURCE_META } from "@/lib/knowledgeData";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { KnowledgeDoc, KnowledgeSource } from "@/types";

const SOURCE_ORDER: KnowledgeSource[] = ["file", "database", "repo", "environment", "chat"];

function DocIcon({ source }: { source: KnowledgeSource }) {
  const meta = SOURCE_META[source];
  const Icon = meta.icon;
  return <Icon className={`w-4 h-4 shrink-0 ${meta.color}`} />;
}

function StatusBadge({ status }: { status: KnowledgeDoc["status"] }) {
  const map = {
    indexed:    { label: "已索引", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800" },
    processing: { label: "处理中", cls: "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800" },
    failed:     { label: "失败",   cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800" },
  } as const;
  const s = map[status];
  return <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-medium border ${s.cls}`}>{s.label}</span>;
}

function QualityBar({ score }: { score: number }) {
  if (score === 0) return <span className="text-[10px] text-muted-foreground">—</span>;
  const color = score >= 90 ? "bg-green-500" : score >= 80 ? "bg-yellow-500" : "bg-red-500";
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-12 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
        <div className={`h-full ${color} rounded-full`} style={{ width: `${score}%` }} />
      </div>
      <span className="text-[10px] text-muted-foreground tabular-nums">{score}</span>
    </div>
  );
}

export function DocumentLibrary() {
  const allDocs = useKnowledgeDocs((s) => s.docs);
  const grouped = useMemo(() => {
    const map = new Map<KnowledgeSource, KnowledgeDoc[]>();
    for (const src of SOURCE_ORDER) {
      const docs = allDocs.filter((d) => d.source === src);
      if (docs.length) map.set(src, docs);
    }
    return map;
  }, [allDocs]);

  return (
    <div className="space-y-6">
      {SOURCE_ORDER.map((src) => {
        const docs = grouped.get(src);
        if (!docs) return null;
        const meta = SOURCE_META[src];
        const Icon = meta.icon;
        return (
          <div key={src}>
            <div className="flex items-center gap-2 mb-3">
              <Icon className={`w-4 h-4 ${meta.color}`} />
              <span className="text-sm font-bold text-foreground">{meta.label}</span>
              <span className="text-xs text-muted-foreground">({docs.length})</span>
            </div>
            <div className="bg-card border border-border rounded-xl overflow-hidden">
              <table className="w-full text-left">
                <thead>
                  <tr className="bg-muted border-b border-border text-xs text-muted-foreground">
                    <th className="p-3 pl-4 font-medium">文档名</th>
                    <th className="p-3 font-medium">Chunks</th>
                    <th className="p-3 font-medium">大小</th>
                    <th className="p-3 font-medium">质量</th>
                    <th className="p-3 font-medium">状态</th>
                    <th className="p-3 font-medium text-right pr-4">更新时间</th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((doc) => (
                    <tr key={doc.id} className="border-b border-border last:border-0 hover:bg-muted/50 transition-colors text-sm">
                      <td className="p-3 pl-4">
                        <div className="flex items-center gap-2.5">
                          <DocIcon source={doc.source} />
                          <span className="font-medium text-foreground">{doc.name}</span>
                        </div>
                      </td>
                      <td className="p-3 text-muted-foreground text-xs tabular-nums">{doc.chunks}</td>
                      <td className="p-3 text-muted-foreground text-xs">{doc.size}</td>
                      <td className="p-3"><QualityBar score={doc.quality} /></td>
                      <td className="p-3"><StatusBadge status={doc.status} /></td>
                      <td className="p-3 text-right pr-4 text-muted-foreground text-xs whitespace-nowrap">{doc.updatedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}

      {allDocs.length === 0 && (
        <div className="py-16 flex flex-col items-center text-muted-foreground">
          <Search className="w-10 h-10 mb-3 opacity-20" />
          <div className="text-sm">没有已摄入的文档</div>
        </div>
      )}
    </div>
  );
}
