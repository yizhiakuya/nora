# 对话 · AI 对话与引用来源（2026-09-04）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

对话是 RAG 的消费端：用户提问 → 模型回答 → 附知识库引用来源，形成“文件→知识库→对话”的闭环。支持多会话、流式呈现、Markdown 渲染。

**非目标**：真实 LLM SSE、向量检索、后端持久化。`MockChatAPI` + `ragService.generateCitations` 本地模拟。

## 2. 页面结构

```
app/chat/page.tsx
├── Header（面包屑 + 模型徽章 + 收藏/分享/删除会话）
└── ChatConversation（消息流 + 输入区）
    ├── ChatMessageItem（用户/助手气泡 + SourceCitations）
    └── ChatInputArea（输入框 + 模型选择 + 发送）
Sidebar: 会话列表（由 useChatSessions 驱动，Header 外独立）
```

- `active` 会话来自 `useChatSessions.activeId`，`key={active.id}` 切换时重建对话状态
- 头部模型徽章取 `useModelProviders.defaultModel`

## 3. 关键交互

| 行为 | 说明 |
|------|------|
| 新建会话 | `useChatSessions.createSession()` 生成 `sess-{timestamp}`，标题初始“新对话” |
| 标题自动命名 | `saveMessages` 中若为“新对话”且首条为用户消息，截前 24 字作为标题 |
| 删除会话 | `deleteSession(id)`，若删当前则切到首个；空时回退 `seedSession` |
| 发送消息 | `useChat.sendMessage` 插入 user + typing 占位，`responder(content, onPartial)` 流式更新，最后 `sources: generateCitations(query, docs)` |
| 引用来源 | `SourceCitations` 渲染 `Citation[]`（docName/source/chunk/score/snippet），与检索测试共用 `ragService` |
| 持久化 | `useChat` 的 `messages` 变化即 `saveMessages` 写回 `useChatSessions`（`persist` → localStorage `chat-sessions`） |

## 4. 状态层

| Hook | 职责 |
|------|------|
| `useChat` (`hooks/useChat.ts`) | 输入/发送状态机（`isSending`/`scrollRef`/`mountedRef` 防卸载更新）、`responder` 可注入 |
| `useChatSessions` (`hooks/useChatSessions.ts`) | 会话列表唯一源：`sessions/activeId/create/delete/setActive/saveMessages`，`persist` |
| `useKnowledgeDocs` | 引用来源的 `docs` 来源 |
| `useModelProviders` (`hooks/useModelProviders.ts`) | 默认模型徽章 |

## 5. 非目标

- 真实 API Key/流式 SSE、对话服务端存储、RAG 向量检索（见 `docs/rag-service.md` 后端接入）

## 6. 验证

- 新建→发送“Redis 配置怎么查”→ 收到引用卡片指向 `Redis 配置说明`
- 刷新后会话与消息保留；删除当前会话自动切走
- `pnpm --dir nora-web build` 正常
