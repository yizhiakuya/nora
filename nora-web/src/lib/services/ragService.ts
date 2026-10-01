import {
  Citation,
  DocDetail,
  IndexStats,
  KnowledgeBase,
  KnowledgeChunk,
  KnowledgeDoc,
  RetrievalLog,
  RetrievalOutcome,
} from "@/types";
import { requestJson } from "@/lib/api/client";

/**
 * RAG 服务层:全部调用方(知识库组件 / 对话页)只依赖本文件导出的函数。
 *
 * 后端端点：
 * - searchDocsAsync    → POST /api/rag/search        { query, topK } → RetrievalResult[]
 * - fetchIndexStats    → GET  /api/rag/index/stats    → IndexStats
 * - generateCitationsAsync → POST /api/rag/citations  { query, topK } → Citation[]
 * - saveTextAsync      → POST /api/rag/index/text      { name, text } → KnowledgeDoc
 */


// ==========================================
// 后端接入层(异步)
// ==========================================

/** 检索：真实混合检索（带通道状态+范围） */
export async function searchDocsAsync(query: string, topK = 8, scope?: { baseId?: number; docIds?: number[] }): Promise<RetrievalOutcome> {
  return requestJson<RetrievalOutcome>("/rag/search", {
    method: "POST",
    body: JSON.stringify({ query, topK, ...(scope ?? {}) }),
  });
}

/** GET /api/rag/bases → 资料库列表（阶段 B） */
export async function fetchBases(): Promise<KnowledgeBase[]> {
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

/** POST /api/rag/docs/{id}/base → 把文档移入资料库（阶段 B;baseId=null 回默认库） */
export async function setDocBase(id: number, baseId: number | null): Promise<KnowledgeDoc> {
  return requestJson<KnowledgeDoc>(`/rag/docs/${id}/base`, {
    method: "POST",
    body: JSON.stringify({ baseId }),
  });
}

/** GET /api/rag/retrievals → 检索记录（阶段 B;最近 N 条） */
export async function fetchRetrievalLogs(limit = 20): Promise<RetrievalLog[]> {
  return requestJson<RetrievalLog[]>(`/rag/retrievals?limit=${limit}`);
}

// ==========================================
// 阶段 D:分段管理 / 分段预览 / 库级配置
// ==========================================

/** PATCH /api/rag/chunks/{id} → 编辑分段正文（自动重算向量） */
export async function updateChunk(chunkId: number, content: string): Promise<KnowledgeChunk[]> {
  return requestJson<KnowledgeChunk[]>(`/rag/chunks/${chunkId}`, {
    method: "PATCH",
    body: JSON.stringify({ content }),
  });
}

/** POST /api/rag/chunks/{id}/enabled → 停用/启用分段 */
export async function setChunkEnabled(chunkId: number, enabled: boolean): Promise<KnowledgeChunk[]> {
  return requestJson<KnowledgeChunk[]>(`/rag/chunks/${chunkId}/enabled`, {
    method: "POST",
    body: JSON.stringify({ enabled }),
  });
}

/** DELETE /api/rag/chunks/{id} → 删除分段（剩余重新编号） */
export async function deleteChunk(chunkId: number): Promise<KnowledgeChunk[]> {
  return requestJson<KnowledgeChunk[]>(`/rag/chunks/${chunkId}`, { method: "DELETE" });
}

/** POST /api/rag/docs/{id}/chunks → 手动新增分段 */
export async function addChunk(docId: number, content: string): Promise<KnowledgeChunk[]> {
  return requestJson<KnowledgeChunk[]>(`/rag/docs/${docId}/chunks`, {
    method: "POST",
    body: JSON.stringify({ content }),
  });
}

/** 分段预览结果 */
export interface ChunkPreview {
  mode: string;
  chunkSize: number;
  overlap: number;
  total: number;
  chunks: { index: number; length: number; content: string; hasParent: boolean }[];
}

/** POST /api/rag/chunk-preview → 分段预览（不落库,导入前调参） */
export async function previewChunks(input: {
  text: string;
  mode?: string;
  chunkSize?: number;
  overlap?: number;
  separator?: string;
}): Promise<ChunkPreview> {
  return requestJson<ChunkPreview>("/rag/chunk-preview", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** PUT /api/rag/bases/{id}/retrieval-config → 库级检索配置（JSON 串） */
export async function setBaseRetrievalConfig(id: number, config: Record<string, unknown>): Promise<KnowledgeBase> {
  return requestJson<KnowledgeBase>(`/rag/bases/${id}/retrieval-config`, {
    method: "PUT",
    body: JSON.stringify({ config: JSON.stringify(config) }),
  });
}

/** 索引统计：后端真实统计 / 本地文档推导 */
export async function fetchIndexStats(): Promise<IndexStats> {
  return requestJson<IndexStats>("/rag/index/stats");
}

/** 对话引用来源（服务端计算） */
export async function generateCitationsAsync(query: string, topK = 2): Promise<Citation[]> {
  const outcome = await requestJson<RetrievalOutcome>("/rag/citations", {
    method: "POST",
    body: JSON.stringify({ query, topK }),
  });
  return outcome?.results ?? [];
}

/** POST /api/rag/index/text → 保存文本到知识库(name-keyed,同名覆盖重建索引) */
export async function saveTextAsync(name: string, text: string): Promise<KnowledgeDoc | null> {
  // A2(2026-09-27):source=chat——对话保存的文档在知识库标记「对话产出」,
  // 不再与通用文本保存混在一起(避免以后检索到旧结论时误以为它是原始资料)
  const doc = await requestJson<KnowledgeDoc>("/rag/index/text", {
    method: "POST",
    body: JSON.stringify({ name, text, source: "chat" }),
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
