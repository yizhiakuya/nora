# RAG 服务层 · 后端 API 契约（2026-09-04;2026-10-01 更新）

> 状态:全部接入真实后端。**前端 Mock 已于 2026-10-01 整体删除**——`ragService.ts` 只保留
> 直连后端 API 的函数,本文档「Mock 评分逻辑」等小节为历史记录。

## 1. 设计原则

所有调用方(知识库组件 / 对话页)只依赖 `src/lib/services/ragService.ts` 导出的函数,
函数体直接调后端 API(`requestJson`),调用方零改动。

## 2. API 契约

| 函数 | 后端端点 | 请求 | 响应 |
|------|---------|------|------|
| `searchDocs(query, docs, topK)` | `POST /api/rag/search` | `{ query, topK }` | `RetrievalResult[]` |
| `computeIndexStats(docs)` | `GET /api/rag/index/stats` | — | `IndexStats` |
| `generateCitations(query, docs, topK)` | `POST /api/rag/citations` | `{ query, topK }` | `Citation[]` |

## 3. 调用方（已接线）

| 组件 | 使用函数 | 说明 |
|------|---------|------|
| `RetrievalTest`（知识库 → 检索测试） | `searchDocsAsync(query, topK)` | 输入问题 → 实时召回相关文档片段 |
| `IndexStatus`（知识库 → 索引状态） | `fetchIndexStats()` | 文档数 / chunks / 待处理 / 健康检查 |
| 对话页 | `generateCitationsAsync(query, topK)` | AI 回答附带知识库引用来源卡片 |

## 4. (历史) Mock 评分逻辑(已删除)

- 中文按单字切分，英文/数字按完整 token
- 文档名匹配（文件名近似替代 chunk 内容，后端返回真实内容）
- 英文关键词（如 `redis`、`orders`）完整命中给保底分 0.62（模拟向量语义召回）
- 结果按分数降序，取 top-K

## 5. 后端接入方式(2026-10-01 收口)

后端端点全部接入,前端 Mock 整体删除:

| 能力 | 实现 |
|------|------|
| 检索 | `searchDocsAsync(query, topK)` → `POST /api/rag/search` |
| 索引统计 | `fetchIndexStats()` → `GET /api/rag/index/stats` |
| 引用来源 | `generateCitationsAsync(query, topK)` → `POST /api/rag/citations` |

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
