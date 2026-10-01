'use client';

import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";

export function KnowledgeBases({ library }: { library: DocumentLibraryState }) {
  const { bases, newBaseName, setNewBaseName, creatingBase, handleCreateBase } = library;
  return (<>
        <div className="bg-card border border-border rounded-xl p-3">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-xs text-muted-foreground shrink-0">资料库:</span>
            {bases.map((b) => (
              <span
                key={b.id}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] border ${b.isDefault
                  ? "bg-blue-50 dark:bg-blue-950/40 border-blue-200 dark:border-blue-800 text-blue-700 dark:text-blue-300"
                  : "bg-muted border-border text-foreground"}`}
                title={b.description ?? undefined}
              >
                {b.name}
                <span className="text-[10px] text-muted-foreground tabular-nums">{b.docCount}</span>
              </span>
            ))}
            <div className="flex items-center gap-1 ml-auto">
              <input
                value={newBaseName}
                onChange={(e) => setNewBaseName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") void handleCreateBase(); }}
                placeholder="新建资料库…"
                className="w-32 px-2 py-1 text-[11px] bg-background border border-border rounded-lg focus:outline-none focus:ring-1 focus:ring-primary"
              />
              <Button
                size="sm"
                variant="outline"
                className="h-6 px-2 text-[11px]"
                disabled={!newBaseName.trim() || creatingBase}
                onClick={() => void handleCreateBase()}
              >
                <Plus className="w-3 h-3" />
              </Button>
            </div>
          </div>
        </div>
  </>);
}
