import { create } from "zustand";
import { persist } from "zustand/middleware";
import { KnowledgeDoc } from "@/types";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { deleteDoc as deleteDocApi, deleteDocs as deleteDocsApi, renameDoc as renameDocApi } from "@/lib/services/ragService";
import type { KnowledgeDoc as BackendKnowledgeDoc } from "@/types";

interface KnowledgeDocsState {
  docs: KnowledgeDoc[];
  /** 对话结论保存为知识库文档（来源 = chat） */
  addChatDoc: (name: string, snippet: string) => KnowledgeDoc;
  /** 文件加入知识库（来源 = file） */
  indexFile: (name: string) => KnowledgeDoc;
  syncFromBackend: () => Promise<void>;
  indexFileFromBackend: (fileId: number, name: string) => Promise<KnowledgeDoc>;
  /** 重命名（后端模式走 PATCH，mock 模式直接改本地） */
  renameDoc: (id: number, name: string) => Promise<void>;
  /** 删除单条；失败时抛错由调用方提示 */
  removeDoc: (id: number) => Promise<void>;
  /** 批量删除，返回实际删除条数 */
  removeDocs: (ids: number[]) => Promise<number>;
}

/** 本地 mock 用的展示时间(与后端 yyyy-MM-dd HH:mm 口径一致) */
function now(): string {
  return new Date()
    .toLocaleString("zh-CN", {
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hour12: false,
    })
    .replace(/\//g, "-");
}

/**
 * 知识库文档唯一数据源：知识库页面（文档库 Tab）与对话页
 * 「保存到知识库」共享，保证跨页可见。
 */
export const useKnowledgeDocs = create<KnowledgeDocsState>()(
  persist(
    (set, get) => ({
      docs: [],
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        const docs = await requestJson<BackendKnowledgeDoc[]>("/rag/docs");
        set({ docs });
      },
      indexFileFromBackend: async (fileId, name) => {
        if (!USE_BACKEND) return get().indexFile(name);
        const doc = await requestJson<BackendKnowledgeDoc>("/rag/index", {
          method: "POST",
          body: JSON.stringify({ fileId, name }),
        });
        set((state) => ({ docs: [doc, ...state.docs.filter((d) => d.id !== doc.id && d.name !== doc.name)] }));
        return doc;
      },
      addChatDoc: (name, snippet) => {
        const doc: KnowledgeDoc = {
          id: Date.now(),
          name,
          source: "chat",
          chunks: Math.max(1, Math.ceil(snippet.length / 512)),
          status: "indexed",
          size: `${Math.max(1, Math.round(snippet.length / 1024))} KB`,
          updatedAt: now(),
          quality: 85,
        };
        set((state) => ({ docs: [doc, ...state.docs] }));
        return doc;
      },
      indexFile: (name) => {
        const doc: KnowledgeDoc = {
          id: Date.now(),
          name,
          source: "file",
          chunks: 6,
          status: "indexed",
          size: "—",
          updatedAt: now(),
          quality: 88,
        };
        set((state) => ({ docs: [doc, ...state.docs] }));
        return doc;
      },

      renameDoc: async (id, name) => {
        const trimmed = name.trim();
        if (!trimmed) throw new Error("文档名不能为空");
        if (USE_BACKEND) {
          // 以服务端返回为准(服务端会更新 updated_at 并校验存在性)
          const updated = await renameDocApi(id, trimmed);
          set((state) => ({
            docs: state.docs.map((d) => (d.id === id ? updated : d)),
          }));
          return;
        }
        set((state) => ({
          docs: state.docs.map((d) => (d.id === id ? { ...d, name: trimmed } : d)),
        }));
      },

      removeDoc: async (id) => {
        if (USE_BACKEND) await deleteDocApi(id);
        set((state) => ({ docs: state.docs.filter((d) => d.id !== id) }));
      },

      removeDocs: async (ids) => {
        if (!ids.length) return 0;
        if (USE_BACKEND) {
          const deleted = await deleteDocsApi(ids);
          // 服务端只删它认得的 id;这里按删除数回写——仅当全删成功才整体移除,
          // 否则重新拉列表对齐真实状态(避免 UI 与库不一致)
          if (deleted === ids.length) {
            const removed = new Set(ids);
            set((state) => ({ docs: state.docs.filter((d) => !removed.has(d.id)) }));
          } else {
            const docs = await requestJson<BackendKnowledgeDoc[]>("/rag/docs");
            set({ docs });
          }
          return deleted;
        }
        const removed = new Set(ids);
        set((state) => ({ docs: state.docs.filter((d) => !removed.has(d.id)) }));
        return ids.length;
      },
    }),
    { name: "knowledge-docs" }
  )
);
