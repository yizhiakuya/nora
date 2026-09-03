import { create } from "zustand";
import { KnowledgeDoc } from "@/types";
import { ALL_DOCS } from "@/lib/knowledgeData";

interface KnowledgeDocsState {
  docs: KnowledgeDoc[];
  /** 对话结论保存为知识库文档（来源 = chat） */
  addChatDoc: (name: string, snippet: string) => KnowledgeDoc;
  /** 文件加入知识库（来源 = file） */
  indexFile: (name: string) => KnowledgeDoc;
}

/**
 * 知识库文档唯一数据源：知识库页面（文档库 Tab）与对话页
 * 「保存到知识库」共享，保证跨页可见。
 */
export const useKnowledgeDocs = create<KnowledgeDocsState>((set) => ({
  docs: ALL_DOCS,
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
}));
