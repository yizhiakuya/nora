import {
  Citation,
  DocDetail,
  IndexStats,
  KnowledgeBase,
  KnowledgeDoc,
  RetrievalLog,
  RetrievalOutcome,
  RetrievalResult,
} from "@/types";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { SOURCE_LABEL } from "@/lib/knowledgeSourceMeta";

/**
 * RAG 服务层（前端 Mock 实现 + 后端 API 接入）。
 *
 * 设计原则：所有调用方（知识库组件 / 对话页）只依赖本文件导出的函数；
 * USE_BACKEND=false 时走本地 Mock（零依赖开发），=true 时走真实后端。
 *
 * 后端端点：
 * - searchDocsAsync    → POST /api/rag/search        { query, topK } → RetrievalResult[]
 * - fetchIndexStats    → GET  /api/rag/index/stats    → IndexStats
 * - generateCitationsAsync → POST /api/rag/citations  { query, topK } → Citation[]
 * - saveTextAsync      → POST /api/rag/index/text      { name, text } → KnowledgeDoc
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

/**
 * 根据查询在文档列表中检索，返回按分数降序的召回结果。
 *
 * 这是**本地回退实现**（USE_BACKEND=false 时使用），基于关键词打分而非向量检索。
 * 后端已接入，USE_BACKEND=true 时调用方应改用 {@link searchDocsAsync}（POST /api/rag/search）。
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
 *
 * 这是**本地回退实现**（USE_BACKEND=false 时使用），vectorDim/model 为前端占位值。
 * 后端已接入，USE_BACKEND=true 时调用方应改用 {@link fetchIndexStats}（GET /api/rag/index/stats），
 * 后者返回后端真实的向量维度与 embedding 模型名。
 */
export function computeIndexStats(docs: KnowledgeDoc[]): IndexStats {
  const totalDocs = docs.length;
  const totalChunks = docs.reduce((sum, d) => sum + d.chunks, 0);
  const pendingDocs = docs.filter((d) => d.status === "processing").length;
  const indexedDocs = docs.filter((d) => d.status === "indexed").length;

  // 找最近更新的文档时间作为 lastUpdate
  const sorted = [...docs].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  const lastUpdate = sorted.length > 0 ? sorted[0].updatedAt : "—";

  return {
    totalDocs,
    totalChunks,
    // 后端未接时不知道真实嵌入配置——不编造(此前硬编码 text-embedding-3-small
    // 与后端实际 jina-embeddings-v3 不符,2026-09-19 去假数据);真实值在
    // USE_BACKEND 时由 fetchIndexStats 覆盖
    vectorDim: 0,
    model: "—",
    lastUpdate,
    pendingDocs,
    vectorReady: indexedDocs > 0,
  };
}

/**
 * 生成对话引用来源（取 top-2 相关文档）。
 *
 * 这是**本地回退实现**（USE_BACKEND=false 时使用），复用 searchDocs 的打分。
 * 后端已接入，USE_BACKEND=true 时调用方应改用 {@link generateCitationsAsync}（POST /api/rag/citations）。
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

// ==========================================
// 后端接入层（USE_BACKEND 开关，异步）
// ==========================================

/** 检索：后端真实混合检索（带通道状态+范围）/ 本地 Mock 模拟 */
export async function searchDocsAsync(query: string, topK = 8, scope?: { baseId?: number; docIds?: number[] }): Promise<RetrievalOutcome> {
  if (!USE_BACKEND) {
    const results = searchDocs(query, useKnowledgeDocs.getState().docs, topK);
    return {
      status: results.length > 0 ? "ok" : "no_match",
      results,
      vector: { ok: true, error: null },
      keyword: { ok: true, error: null },
    };
  }
  return requestJson<RetrievalOutcome>("/rag/search", {
    method: "POST",
    body: JSON.stringify({ query, topK, ...(scope ?? {}) }),
  });
}

/** GET /api/rag/bases → 资料库列表（阶段 B） */
export async function fetchBases(): Promise<KnowledgeBase[]> {
  if (!USE_BACKEND) return [];
  return requestJson<KnowledgeBase[]>("/rag/bases");
}

/** POST /api/rag/bases → 新建资料库 */
export async function createBase(name: string, description?: string): Promise<KnowledgeBase> {
  return requestJson<KnowledgeBase>("/rag/bases", {
    method: "POST",
    body: JSON.stringify({ name, description }),
  });
}

/** DELETE /api/rag/bases/{id} → 删除资料库（默认库拒绝;库内文档回默认库） */
export async function deleteBase(id: number): Promise<void> {
  await requestJson(`/rag/bases/${id}`, { method: "DELETE" });
}

/** POST /api/rag/docs/{id}/enabled → 文档停用/启用（阶段 B;停用=退出检索） */
export async function setDocEnabled(id: number, enabled: boolean): Promise<KnowledgeDoc> {
  return requestJson<KnowledgeDoc>(`/rag/docs/${id}/enabled`, {
    method: "POST",
    body: JSON.stringify({ enabled }),
  });
}

/** GET /api/rag/retrievals → 检索记录（阶段 B;最近 N 条） */
export async function fetchRetrievalLogs(limit = 20): Promise<RetrievalLog[]> {
  if (!USE_BACKEND) return [];
  return requestJson<RetrievalLog[]>(`/rag/retrievals?limit=${limit}`);
}

/** 索引统计：后端真实统计 / 本地文档推导 */
export async function fetchIndexStats(): Promise<IndexStats> {
  if (!USE_BACKEND) {
    return computeIndexStats(useKnowledgeDocs.getState().docs);
  }
  return requestJson<IndexStats>("/rag/index/stats");
}

/** 对话引用来源：后端真实 / 本地 Mock 模拟 */
export async function generateCitationsAsync(query: string, topK = 2): Promise<Citation[]> {
  if (!USE_BACKEND) {
    return generateCitations(query, useKnowledgeDocs.getState().docs, topK);
  }
  const outcome = await requestJson<RetrievalOutcome>("/rag/citations", {
    method: "POST",
    body: JSON.stringify({ query, topK }),
  });
  return outcome?.results ?? [];
}

/** POST /api/rag/index/text → 保存文本到知识库(name-keyed,同名覆盖重建索引) */
export async function saveTextAsync(name: string, text: string): Promise<KnowledgeDoc | null> {
  const doc = await requestJson<KnowledgeDoc>("/rag/index/text", {
    method: "POST",
    body: JSON.stringify({ name, text }),
  });
  return doc ?? null;
}

// ==========================================
// 文档 CRUD(知识库文档库)
// ==========================================

/** GET /api/rag/docs/{id} → 文档详情 + chunk 正文列表 */
export async function fetchDocDetail(id: number): Promise<DocDetail> {
  return requestJson<DocDetail>(`/rag/docs/${id}`);
}

/** PATCH /api/rag/docs/{id} → 重命名(不动 chunk 与向量,便宜且安全) */
export async function renameDoc(id: number, name: string): Promise<KnowledgeDoc> {
  return requestJson<KnowledgeDoc>(`/rag/docs/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ name }),
  });
}

/** DELETE /api/rag/docs/{id} → 删除单条(chunk 由外键级联删除) */
export async function deleteDoc(id: number): Promise<void> {
  await requestJson<void>(`/rag/docs/${id}`, { method: "DELETE" });
}

/**
 * POST /api/rag/docs/delete → 批量删除。
 * 走 POST 而非 DELETE:批量 id 放 body 更稳(DELETE 带 body 在部分代理/网关会被丢)。
 */
export async function deleteDocs(ids: number[]): Promise<number> {
  const res = await requestJson<{ deleted: number }>("/rag/docs/delete", {
    method: "POST",
    body: JSON.stringify({ ids }),
  });
  return res?.deleted ?? 0;
}

/** POST /api/rag/docs/{id}/reindex → 用已存 chunk 正文重建向量(换模型/失败恢复) */
export async function reindexDoc(id: number): Promise<KnowledgeDoc> {
  return requestJson<KnowledgeDoc>(`/rag/docs/${id}/reindex`, { method: "POST" });
}
