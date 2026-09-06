# RAG 服务层 · 后端 API 契约预留（2026-09-04）

> 状态：已实现（前端 Mock）· 目标：业务 mock 走通，给 API 留好位置，后端接入只改一个文件

## 1. 设计原则

所有调用方（知识库组件 / 对话页）只依赖 `src/lib/services/ragService.ts` 导出的函数。
当前函数体是前端 Mock（本地文档列表 + 关键词评分），后端接入时只需将函数体替换为
`fetch` 请求，调用方零改动。

## 2. API 契约

| 函数 | 后端端点 | 请求 | 响应 |
|------|---------|------|------|
| `searchDocs(query, docs, topK)` | `POST /api/rag/search` | `{ query, topK }` | `RetrievalResult[]` |
| `computeIndexStats(docs)` | `GET /api/rag/index/stats` | — | `IndexStats` |
| `generateCitations(query, docs, topK)` | `POST /api/rag/citations` | `{ query, topK }` | `Citation[]` |

## 3. 调用方（已接线）

| 组件 | 使用函数 | 说明 |
|------|---------|------|
| `RetrievalTest`（知识库 → 检索测试） | `searchDocs(query, docs)` | 输入问题 → 实时召回相关文档片段 |
| `IndexStatus`（知识库 → 索引状态） | `computeIndexStats(docs)` | 文档数 / chunks / 待处理 / 健康检查 |
| `MockChatAPI.sendMessage`（对话页） | `generateCitations(message, docs)` | AI 回答附带知识库引用来源卡片 |

## 4. 当前 Mock 评分逻辑

- 中文按单字切分，英文/数字按完整 token
- 文档名匹配（文件名近似替代 chunk 内容，后端返回真实内容）
- 英文关键词（如 `redis`、`orders`）完整命中给保底分 0.62（模拟向量语义召回）
- 结果按分数降序，取 top-K

## 5. 后端接入方式（已于 2026-09 完成）

后端三个端点已实现并接入，接入方式不是替换原函数体，而是**新增异步版本、保留同步 Mock 作为回退**：

| 能力 | Mock 回退（`USE_BACKEND=false`） | 后端实现（`USE_BACKEND=true`） |
|------|------------------------------|---------------------------|
| 检索 | `searchDocs(query, docs, topK)` | `searchDocsAsync(query, topK)` → `POST /api/rag/search` |
| 索引统计 | `computeIndexStats(docs)` | `fetchIndexStats()` → `GET /api/rag/index/stats` |
| 引用来源 | `generateCitations(query, docs, topK)` | `generateCitationsAsync(query, topK)` → `POST /api/rag/citations` |

同步函数保留供 `USE_BACKEND=false` 与离线演示使用，其 JSDoc 已注明为回退实现。
各调用方按 `USE_BACKEND` 分流选择同步或异步版本，组件层无侵入。

## 6. 验收记录（2026-09-04）

- 检索测试输入「Redis 连接失败」→ 召回「Redis 配置说明」（62%），不再是固定 4 条假数据
- 索引状态实时显示 24 文档 / 412 chunks / 待处理 1（源自真实文档列表，非静态常量）
- 对话页发送「redis 配置怎么查」→ 引用来源卡片动态返回「Redis 配置说明」片段
- typecheck / lint / 45 tests / build 全绿

## 7. 提交

```
e3535e8 feat: ragService layer — API-ready search/stats/citations wired to knowledge + chat
fb0b652 fix: ragService retrieval scoring — English keyword hit gives vector-like floor score
```
