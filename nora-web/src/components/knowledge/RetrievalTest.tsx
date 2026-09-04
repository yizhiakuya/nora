'use client';

import { useState } from "react";
import { SearchCode, Loader2, Database, FileCode, Server, MessageSquare, File } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { SOURCE_META } from "@/lib/knowledgeData";
import { searchDocs } from "@/lib/services/ragService";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { KnowledgeSource, RetrievalResult } from "@/types";

const SOURCE_ICONS: Record<KnowledgeSource, React.ElementType> = {
  file: File,
  database: Database,
  repo: FileCode,
  environment: Server,
  chat: MessageSquare,
};

function ScoreColor({ score }: { score: number }) {
  if (score >= 0.9) return "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800";
  if (score >= 0.8) return "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800";
  return "bg-gray-50 dark:bg-gray-800 text-muted-foreground border-border";
}

function HighlightSnippet({ text, query }: { text: string; query: string }) {
  const terms = query.split(/\s+/).filter((t) => t.length > 1);
  if (!terms.length) return <>{text}</>;
  const regex = new RegExp(`(${terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  const parts = text.split(regex);
  return (
    <>
      {parts.map((part, i) =>
        regex.test(part) ? (
          <mark key={i} className="bg-yellow-200/60 dark:bg-yellow-900/40 text-yellow-900 dark:text-yellow-200 px-0.5 rounded">
            {part}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        )
      )}
    </>
  );
}

export function RetrievalTest() {
  const [isSearching, setIsSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [query, setQuery] = useState("");
  const [lastQuery, setLastQuery] = useState("");
  const { schedule, cancelAll } = useTimedSequence();
  const [searchResults, setSearchResults] = useState<RetrievalResult[]>([]);

  const handleSearch = () => {
    if (!query.trim()) return;
    cancelAll();
    setIsSearching(true);
    setHasSearched(false);
    schedule(() => {
      setIsSearching(false);
      setLastQuery(query);
      setHasSearched(true);
      setSearchResults(searchDocs(query, useKnowledgeDocs.getState().docs));
    }, 800);
  };

  return (
    <div className="space-y-4">
      <div className="bg-card border border-border rounded-xl p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-bold text-foreground">检索测试</h3>
          <span className="text-[10px] text-muted-foreground">模拟向量 + 关键词混合检索</span>
        </div>
        <div className="flex gap-2">
          <div className="relative flex-1">
            <SearchCode className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted-foreground" />
            <input
              className="w-full pl-9 pr-3 py-2 h-9 bg-muted border border-border rounded-lg text-xs focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-500"
              placeholder="输入问题，如：Redis 连接失败怎么排查？"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") handleSearch(); }}
            />
          </div>
          <Button size="sm" className="h-9 px-4 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSearch} disabled={isSearching || !query.trim()}>
            {isSearching ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <SearchCode className="w-3.5 h-3.5" />}
            检索
          </Button>
        </div>
      </div>

      {isSearching && (
        <div className="py-12 flex flex-col items-center text-muted-foreground gap-3">
          <Loader2 className="w-6 h-6 animate-spin text-blue-500 dark:text-blue-400" />
          <span className="text-xs animate-pulse">正在向量检索…</span>
        </div>
      )}

      {!isSearching && !hasSearched && (
        <div className="py-12 flex flex-col items-center text-muted-foreground gap-2">
          <SearchCode className="w-8 h-8 opacity-20" />
          <span className="text-xs">输入问题测试知识库召回效果</span>
        </div>
      )}

      {!isSearching && hasSearched && (
        <div className="space-y-3">
          <div className="text-xs text-muted-foreground">
            召回结果 <span className="font-bold text-foreground">{searchResults.length}</span> 条 ·
            耗时 <span className="tabular-nums">34ms</span>
          </div>
          {searchResults.map((r, i) => {
            const Icon = SOURCE_ICONS[r.source];
            const meta = SOURCE_META[r.source];
            return (
              <div
                key={i}
                className="bg-card border border-border rounded-xl p-4 relative overflow-hidden animate-in fade-in slide-in-from-bottom-2"
                style={{ animationDelay: `${i * 80}ms`, animationFillMode: "backwards" }}
              >
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-blue-500" style={{ opacity: r.score }} />
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <Icon className={`w-3.5 h-3.5 ${meta.color}`} />
                    <span className="text-xs font-medium text-foreground">{r.docName}</span>
                    <span className="text-[10px] text-muted-foreground">chunk #{r.chunkIndex}</span>
                  </div>
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border tabular-nums ${ScoreColor({ score: r.score })}`}>
                    {(r.score * 100).toFixed(0)}%
                  </span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  <HighlightSnippet text={r.snippet} query={lastQuery} />
                </p>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}


