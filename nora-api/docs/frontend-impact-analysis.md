# 前端影响分析 · agent-service 后端接入

> 日期：2026-09-04  
> 范围：nora-web 需要的改动 / 不需要改的部分 / 保留本地 Mock 的回退策略

---

## 结论先行

**大部分不需要改，少部分需要改，一部分需要新增。**

| 改动类型 | 数量 | 说明 |
|---------|------|------|
| ✅ 零改动 | ~70% | 数据结构完全对齐后端实体 |
| 🔧 需要改 | ~20% | Mock 调用替换为真实 API + SSE 事件解析 |
| ➕ 需要新增 | ~10% | Guardrail 拒绝提示、Human-in-the-loop 审批 UI、Agent 轨迹详情 |

---

## 一、零改动（结构已对齐）

以下前端类型 / 组件 / store 字段与后端设计**一一对应**，后端就绪后**完全不需要改**：

### 1. 数据类型（`src/types/index.ts`）

| 前端类型 | 后端实体（v2 文档 4.9 节） | 状态 |
|---------|--------------------------|------|
| `KnowledgeDoc` | `knowledge_doc` 表 | ✅ 字段完全对齐 |
| `IndexStats` | `IndexStatistics` | ✅ |
| `RetrievalResult` | `RetrievalResult` | ✅ |
| `Citation` | `Citation` | ✅ |
| `KnowledgeSource` | enum | ✅ |
| `FileItem` | `file_item` 表 | ✅（`icon/color` 由前端本地映射） |
| `FilePreview` | `FilePreview` | ✅ |

### 2. Chat 数据结构（`src/lib/api/chatApi.ts`）

| 前端 | 后端 SSE 事件 | 状态 |
|------|-------------|------|
| `ChatStep.type: "think"\|"tool"` | agent_step 的 `think / tool_call / observation` | ✅ 语义一致 |
| `ChatStep.status: pending/running/completed/failed` | 同 | ✅ |
| `ChatStep.duration` | `duration_ms` | ✅（需格式化） |
| `ChatMessage.sources[]` | SSE `sources` 事件 | ✅ |
| `ChatMessage.isTyping` | SSE `delta` 事件流 | ✅ |

### 3. 组件渲染逻辑

- `ChatMessageItem.tsx`：已按 `step.type` 分 `think`（🧠）和 `tool`（🔧）渲染，与后端 `agent_step.step_type` 完全对应
- `ChatInputArea.tsx`：AI 能力状态条（skills）已有启停开关，对应后端 Tools 注册表
- `ChatConversation.tsx`：流式更新机制 `isTyping` → `content` 已就绪

### 4. 状态 Stores（结构兼容）

| Store | 后端表 | 兼容性 |
|-------|--------|--------|
| `useModelProviders` | `model_provider` | ✅ `protocol` 枚举一致（openai/ollama/anthropic） |
| `useFiles` | `file_item` | ✅ `partialize` 已处理 icon 序列化 |
| `useKnowledgeDocs` | `knowledge_doc` | ✅ |
| `useChatSessions` | `chat_session` + `chat_message` | ✅ |
| `useSkills` | agent tools 注册表 | ✅ |

---

## 二、需要改（Mock → 真实 API）

### 2.1 `src/lib/api/chatApi.ts`（核心改动）

**现状**：`MockChatAPI.sendMessage()` 用 setTimeout 模拟流式返回  
**改为**：SSE 连接后端 `POST /api/chat/sessions/{id}/messages`

```typescript
// 现在（Mock）
export const MockChatAPI: { sendMessage: ChatResponder } = {
  async sendMessage(_message, onUpdate) { ... }
};

// 改为（真实 SSE）
export const AgentAPI: { sendMessage: ChatResponder } = {
  async sendMessage(message, onUpdate, sessionId?) {
    const res = await fetch(`/api/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content: message }),
    });

    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // 解析 SSE 事件
      const lines = buffer.split("\n\n");
      buffer = lines.pop()!;
      for (const line of lines) {
        const event = parseSSE(line); // event: step / delta / sources / done
        switch (event.type) {
          case "step":
            onUpdate({ steps: [...currentSteps, event.step] });
            break;
          case "delta":
            onUpdate({ content: (currentContent + event.content) });
            break;
          case "sources":
            onUpdate({ sources: event.sources });
            break;
          case "done":
            onUpdate({ isTyping: false });
            break;
        }
      }
    }
  }
};
```

**新增**：`ChatResponder` 类型需要扩展可选 `sessionId` 参数。

### 2.2 `src/hooks/useChat.ts`

```typescript
// 现状
await responder(content, (partial) => updateMessage(assistantMsgId, partial));

// 改为：传入 sessionId
await responder(content, (partial) => updateMessage(assistantMsgId, partial), sessionId);
```

### 2.3 `src/lib/services/ragService.ts`（三个函数）

已有预留（文档 v1 第 5 章已说明），函数签名不变，函数体改 fetch。

### 2.4 Vite 代理配置（`vite.config.ts`）

```typescript
// 新增，让 /api 转发到 gateway-service :8080
server: {
  port: 3001,
  proxy: {
    "/api": {
      target: "http://localhost:8080",
      changeOrigin: true,
    },
  },
},
```

### 2.5 其余 Mock Store 渐进替换

| Store | 替换时机 | 改动量 |
|-------|---------|--------|
| `useFiles` | Phase 1（文件上传真实化） | 中等（addFile → upload API） |
| `useModelProviders` | Phase 2（模型管理真实化） | 中等（CRUD + test API） |
| `useChatSessions` | Phase 2（会话后端持久化） | 中等（saveMessages → 后端同步） |
| `useSkills` | Phase 2（工具注册表） | 小 |
| `useConnections` | Phase 3（数据源） | 中等 |
| `useAutomations` | Phase 3（自动任务） | 中等 |
| `useServices` | Phase 4（环境服务） | 中等 |

---

## 三、需要新增（后端新能力对应 UI）

### 3.1 Guardrail 拒绝提示

后端四层 Guardrail 触发 tripwire 后，SSE 会推 `step(status=failed, type=tool)`。前端需要：

- `ChatMessageItem` 已支持 `failed` 状态渲染（红色）
- **新增**：失败原因文案（后端返回 `detail` 字段）展示
- **新增**：被拦截的工具名 + 拦截层（input/tool-input/tool-output/output）

### 3.2 Human-in-the-loop 审批 UI

后端 `humanInTheLoopBuilder()` 在高风险工具调用前 interrupt。前端需要新增：

- 消息气泡内嵌「⚠️ 需要确认」卡片
- 显示：将要执行的操作（如 `DELETE FROM files`）+ 影响范围
- 两个按钮：`批准执行` / `取消`
- 点击后 POST `/api/chat/steps/{id}/approve` 或 `/reject`

这是**全新的 UI 组件**（`ApprovalStepCard.tsx`），需要设计稿。

### 3.3 Agent 轨迹详情

后端 `agent_step` 表逐 step 持久化。前端可新增：

- 点击 tool step → 展开 `tool_input`（如 SQL 语句）和 `tool_output`（如查询结果）
- 对话历史回放时能看到每步的工具参数与返回值

前端 `ChatStep` 需扩展可选字段：

```typescript
export interface ChatStep {
  id: string;
  type: "think" | "tool";
  title: string;
  detail?: string;
  duration?: string;
  status: "pending" | "running" | "completed" | "failed";
  // ===== 新增（可选，向后兼容）=====
  toolName?: string;      // 后端 agent_step.tool_name
  toolInput?: unknown;    // 后端 agent_step.tool_input (JSONB)
  toolOutput?: unknown;   // 后端 agent_step.tool_output (JSONB)
}
```

### 3.4 Reflexion 失败提示

后端 `agent_reflection` 存储失败反思。前端可在消息下方展示：

- 「Agent 已从上次失败中学习」小徽章
- hover 展示反思内容

### 3.5 Skill Library 展示

后端 `agent_skill` 表存储已验证的调用链。前端「AI 能力中心」可新增 Tab：

- 展示已沉淀的技能（name + description + success_count）
- 对应现有 `SkillCard` 组件扩展

---

## 四、改动量汇总

| 阶段 | 前端文件改动 | 新增组件 | 工作量 |
|------|-------------|---------|--------|
| Phase 1（RAG） | `ragService.ts` + `vite.config.ts` | 0 | **小**（~1 天） |
| Phase 2（Agent） | `chatApi.ts` + `useChat.ts` + `vite.config.ts` | `ApprovalStepCard.tsx` | **中**（~3 天） |
| Phase 2 扩展 | `ChatStep` 类型 + `ChatMessageItem.tsx` | 轨迹详情展开组件 | **中**（~1 天） |
| Phase 3 | `useConnections` + `useAutomations` 渐进替换 | 0 | **中**（~2 天） |
| Phase 4 | `useServices` 替换 | 0 | **中**（~1 天） |

总计：**~8 个前端工作日**，其中约 70% 是结构对齐后的渐进替换，30% 是新增 UI。

---

## 五、回退策略

所有 API 层保留环境变量开关：

```typescript
// src/lib/api/client.ts
const USE_BACKEND = import.meta.env.VITE_USE_BACKEND === "true";

// useChat.ts
const responder = USE_BACKEND ? AgentAPI.sendMessage : MockChatAPI.sendMessage;
```

- `VITE_USE_BACKEND=false`（默认）：继续用本地 Mock，后端不可用不影响前端开发
- `VITE_USE_BACKEND=true`：切换到真实后端
- 切换是**零成本**的，因为 `ChatResponder` 接口签名保持不变

这允许前后端并行开发，后端未就绪时前端不阻塞。