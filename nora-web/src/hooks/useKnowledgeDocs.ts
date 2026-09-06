import { create } from "zustand";
import { persist } from "zustand/middleware";
import { KnowledgeDoc } from "@/types";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import type { KnowledgeDoc as BackendKnowledgeDoc } from "@/types";

interface KnowledgeDocsState {
  docs: KnowledgeDoc[];
  /** 对话结论保存为知识库文档（来源 = chat） */
  addChatDoc: (name: string, snippet: string) => KnowledgeDoc;
  /** 文件加入知识库（来源 = file） */
  indexFile: (name: string) => KnowledgeDoc;
  syncFromBackend: () => Promise<void>;
  indexFileFromBackend: (fileId: number, name: string) => Promise<KnowledgeDoc>;
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
          updatedAt: new Date().toLocaleString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).replace(/\//g, "-"),
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
          updatedAt: new Date().toLocaleString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).replace(/\//g, "-"),
          quality: 88,
        };
        set((state) => ({ docs: [doc, ...state.docs] }));
        return doc;
      },
    }),
    { name: "knowledge-docs" }
  )
);
