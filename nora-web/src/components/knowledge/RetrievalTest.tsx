'use client';

import { useState } from "react";
import { SearchCode, Loader2, Database, FileCode, Server, MessageSquare, File } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { SOURCE_META } from "@/lib/knowledgeSourceMeta";
import { searchDocsAsync } from "@/lib/services/ragService";
import { USE_BACKEND } from "@/lib/api/client";
import { KnowledgeSource, RetrievalOutcome } from "@/types";

const SOURCE_ICONS: Record<KnowledgeSource, React.ElementType> = {
  file: File,
  database: Database,
  repo: FileCode,
  environment: Server,
  chat: MessageSquare,
  text: MessageSquare,
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
  const [outcome, setOutcome] = useState<RetrievalOutcome | null>(null);
  const [searchMs, setSearchMs] = useState(34);
  /** 展开查看完整证据的条目序号(阶段 A:预览与模型证据分离) */
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const searchResults = outcome?.results ?? [];

  const handleSearch = async () => {
    if (!query.trim()) return;
    cancelAll();
    setIsSearching(true);
    setHasSearched(false);
    setExpanded(new Set());
    const startedAt = performance.now();
    const run = async () => {
      try {
        const result = await searchDocsAsync(query, 8);
        setOutcome(result);
        setLastQuery(query);
        setHasSearched(true);
        setSearchMs(Math.max(1, Math.round(performance.now() - startedAt)));
      } finally {
        setIsSearching(false);
      }
    };
    // Mock 模式保留 800ms 演示延迟；真实后端直接请求
    if (USE_BACKEND) {
      await run();
    } else {
      schedule(() => { void run(); }, 800);
    }
  };

  return (
    <div className="space-y-4">
      <div className="bg-card border border-border rounded-xl p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-bold text-foreground">检索测试</h3>
          <span className="text-[10px] text-muted-foreground">{USE_BACKEND ? "pgvector 语义检索" : "模拟向量 + 关键词混合检索"}</span>
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
          {/* 通道状态(阶段 A):degraded/unavailable 显式展示,不再「空结果」错觉 */}
          {outcome && (outcome.status === "degraded" || outcome.status === "unavailable") && (
            <div className={`text-xs rounded-lg border px-3 py-2 ${outcome.status === "unavailable"
              ? "bg-red-50 dark:bg-red-950/40 border-red-200 dark:border-red-800 text-red-700 dark:text-red-300"
              : "bg-amber-50 dark:bg-amber-950/40 border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-300"}`}>
              {outcome.status === "unavailable" ? "检索暂不可用（全部通道失败）" : "检索降级（部分通道失败,结果可能不全）"}
              {!outcome.vector.ok && outcome.vector.error && <div className="mt-0.5 opacity-80">向量通道:{outcome.vector.error}</div>}
              {!outcome.keyword.ok && outcome.keyword.error && <div className="mt-0.5 opacity-80">关键词通道:{outcome.keyword.error}</div>}
            </div>
          )}
          <div className="text-xs text-muted-foreground">
            召回结果 <span className="font-bold text-foreground">{searchResults.length}</span> 条 ·
            耗时 <span className="tabular-nums">{searchMs}ms</span>
            {outcome?.status === "no_match" && <span className="ml-2">· 没有过阈命中（可换关键词或降低 min-score 测试）</span>}
          </div>
          {searchResults.map((r, i) => {
            const Icon = SOURCE_ICONS[r.source];
            const meta = SOURCE_META[r.source];
            const isExpanded = expanded.has(i);
            const hasFullContent = !!(r.content && r.content !== r.snippet);
            return (
              <div
                key={i}
                className="bg-card border border-border rounded-xl p-4 relative overflow-hidden animate-in fade-in slide-in-from-bottom-2"
                style={{ animationDelay: `${i * 80}ms`, animationFillMode: "backwards" }}
              >
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-blue-500" style={{ opacity: r.score }} />
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2 min-w-0 flex-wrap">
                    <Icon className={`w-3.5 h-3.5 ${meta.color}`} />
                    <span className="text-xs font-medium text-foreground">{r.docName}</span>
                    <span className="text-[10px] text-muted-foreground">chunk #{r.chunkIndex}</span>
                    {r.chunkId != null && <span className="text-[10px] text-muted-foreground/70">#{r.chunkId}</span>}
                    {r.matchChannel && (
                      <span className="text-[9px] px-1.5 py-0.5 rounded-full border border-border text-muted-foreground"
                        title={`向量分:${r.vectorScore != null ? r.vectorScore.toFixed(3) : "—"} · 关键词分:${r.keywordScore != null ? r.keywordScore.toFixed(3) : "—"}`}>
                        {r.matchChannel === "both" ? "双通道" : r.matchChannel === "vector" ? "向量" : "关键词"}
                      </span>
                    )}
                  </div>
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border tabular-nums shrink-0 ${ScoreColor({ score: r.score })}`}>
                    {r.score.toFixed(3)}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  <HighlightSnippet text={isExpanded && r.content ? r.content : r.snippet} query={lastQuery} />
                </p>
                {hasFullContent && (
                  <button
                    type="button"
                    onClick={() => setExpanded((prev) => {
                      const next = new Set(prev);
                      if (next.has(i)) next.delete(i); else next.add(i);
                      return next;
                    })}
                    className="mt-1.5 text-[10px] text-blue-500 dark:text-blue-400 hover:underline cursor-pointer"
                  >
                    {isExpanded ? "收起（回到命中预览）" : `展开完整证据（${r.content!.length} 字符,模型实际读到的内容）`}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}


