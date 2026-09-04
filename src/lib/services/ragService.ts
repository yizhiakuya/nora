import {
  Citation,
  IndexStats,
  KnowledgeDoc,
  KnowledgeSource,
  RetrievalResult,
} from "@/types";

/**
 * RAG 服务层（前端 Mock 实现 + 后端 API 契约预留）。
 *
 * 设计原则：所有调用方（知识库组件 / 对话页）只依赖本文件导出的函数，
 * 后端接入时只需将函数体替换为 fetch 请求，调用方零改动。
 *
 * 后端端点约定：
 * - searchDocs        → POST /api/rag/search        { query, topK } → RetrievalResult[]
 * - computeIndexStats → GET  /api/rag/index/stats    → IndexStats
 * - generateCitations → POST /api/rag/citations      { query, topK } → Citation[]
 */

/** 将查询拆分为检索词：中文按单字，英文/数字按完整 token */
export function tokenizeQuery(query: string): string[] {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) return [];
  const englishTokens = trimmed.match(/[a-z0-9_.-]{2,}/g) ?? [];
  const chineseChars = trimmed.match(/[\u4e00-\u9fff]/g) ?? [];
  return Array.from(new Set([...englishTokens, ...chineseChars]));
}

/** 从文档名 + 模拟 snippet 中生成一段可读文本用于评分 */
function docText(doc: KnowledgeDoc): string {
  // 后端会返回真实 chunk 内容；前端用文件名近似替代
  return doc.name.toLowerCase();
}

function scoreDoc(doc: KnowledgeDoc, tokens: string[]): number {
  if (!tokens.length) return 0;
  const text = docText(doc);
  let matched = 0;
  for (const token of tokens) {
    const isEnglish = /[a-z0-9]/.test(token[0]);
    if (text.includes(token)) {
      // 英文/数字关键词（如 redis、orders）是强信号，命中即接近满分；
      // 中文字符按弱信号累计，避免单字误匹配拉高整体分数。
      matched += isEnglish ? 1 : 0.5;
    }
  }
  if (matched === 0) return 0;
  const base = matched / tokens.length;
  // 任一英文关键词完整命中时给予召回保底分，模拟向量语义召回
  const englishHit = tokens.some((t) => /[a-z0-9]/.test(t[0]) && text.includes(t));
  const score = englishHit ? Math.max(0.62, base) : base;
  return Number(Math.min(0.97, score).toFixed(2));
}

function snippetFor(doc: KnowledgeDoc, query: string): string {
  // 后端返回真实 chunk 内容；前端生成语义占位
  const meta = SOURCE_LABEL[doc.source] ?? doc.source;
  return `…来自「${doc.name}」（${meta}）的片段，与「${query}」相关…`;
}

const SOURCE_LABEL: Record<KnowledgeSource, string> = {
  file: "文件上传",
  database: "数据库",
  repo: "代码仓库",
  environment: "环境配置",
  chat: "对话产出",
};

/**
 * 根据查询在文档列表中检索，返回按分数降序的召回结果。
 * TODO: 后端接入后替换为 fetch("/api/rag/search", { method: "POST", body: JSON.stringify({ query, topK }) })
 */
export function searchDocs(query: string, docs: KnowledgeDoc[], topK = 8): RetrievalResult[] {
  const tokens = tokenizeQuery(query);
  if (!tokens.length) return [];

  return docs
    .map((doc): RetrievalResult => {
      const score = scoreDoc(doc, tokens);
      return {
        docName: doc.name,
        source: doc.source,
        chunkIndex: Math.max(0, Math.min(doc.chunks - 1, Math.floor(doc.name.length % Math.max(doc.chunks, 1)))),
        score,
        snippet: snippetFor(doc, query),
      };
    })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, topK);
}

/**
 * 从真实文档列表推导索引统计，替代静态 MOCK_INDEX_STATS。
 * TODO: 后端接入后替换为 fetch("/api/rag/index/stats") → IndexStats
 */
export function computeIndexStats(docs: KnowledgeDoc[]): IndexStats {
  const totalDocs = docs.length;
  const totalChunks = docs.reduce((sum, d) => sum + d.chunks, 0);
  const pendingDocs = docs.filter((d) => d.status === "processing").length;
  const indexedDocs = docs.filter((d) => d.status === "indexed").length;
  const failedDocs = docs.filter((d) => d.status === "failed").length;

  // 找最近更新的文档时间作为 lastUpdate
  const sorted = [...docs].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  const lastUpdate = sorted.length > 0 ? sorted[0].updatedAt : "—";

  return {
    totalDocs,
    totalChunks,
    vectorDim: 1536,
    model: "text-embedding-3-small",
    lastUpdate,
    pendingDocs,
    vectorReady: indexedDocs > 0,
    graphReady: indexedDocs > 0 && failedDocs === 0,
  };
}

/**
 * 生成对话引用来源（取 top-2 相关文档）。
 * TODO: 后端接入后替换为 fetch("/api/rag/citations", { method: "POST", body: JSON.stringify({ query, topK: 2 }) })
 */
export function generateCitations(query: string, docs: KnowledgeDoc[], topK = 2): Citation[] {
  return searchDocs(query, docs, topK).map((r) => ({
    docName: r.docName,
    source: r.source,
    chunkIndex: r.chunkIndex,
    score: r.score,
    snippet: r.snippet,
  }));
}


