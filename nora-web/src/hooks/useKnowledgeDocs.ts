import { create } from "zustand";
import { persist } from "zustand/middleware";
import { KnowledgeDoc } from "@/types";
import { requestJson } from "@/lib/api/client";
import { deleteDoc as deleteDocApi, deleteDocs as deleteDocsApi, renameDoc as renameDocApi } from "@/lib/services/ragService";
import type { KnowledgeDoc as BackendKnowledgeDoc } from "@/types";

interface KnowledgeDocsState {
  docs: KnowledgeDoc[];
  syncFromBackend: () => Promise<void>;
  /**
   * 文件索引进知识库（阶段 D:可带分段配置与资料库）。
   *
   * @param chunkConfig 分段配置(可选):{mode, chunkSize, overlap, separator}
   * @param baseId      目标资料库;null=默认库
   */
  indexFileFromBackend: (fileId: number, name: string,
                         chunkConfig?: { mode?: string; chunkSize?: number; overlap?: number; separator?: string },
                         baseId?: number | null) => Promise<KnowledgeDoc>;
  /** 重命名(走服务端 PATCH) */
  renameDoc: (id: number, name: string) => Promise<void>;
  /** 删除单条；失败时抛错由调用方提示 */
  removeDoc: (id: number) => Promise<void>;
  /** 批量删除，返回实际删除条数 */
  removeDocs: (ids: number[]) => Promise<number>;
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
        const docs = await requestJson<BackendKnowledgeDoc[]>("/rag/docs");
        set({ docs });
      },
      indexFileFromBackend: async (fileId, name, chunkConfig, baseId) => {
        const body: Record<string, unknown> = { fileId, name };
        if (chunkConfig?.mode) body.chunkMode = chunkConfig.mode;
        if (chunkConfig?.chunkSize != null) body.chunkSize = chunkConfig.chunkSize;
        if (chunkConfig?.overlap != null) body.overlap = chunkConfig.overlap;
        if (chunkConfig?.separator) body.separator = chunkConfig.separator;
        if (baseId != null) body.baseId = baseId;
        const doc = await requestJson<BackendKnowledgeDoc>("/rag/index", {
          method: "POST",
          body: JSON.stringify(body),
        });
        set((state) => ({ docs: [doc, ...state.docs.filter((d) => d.id !== doc.id && d.name !== doc.name)] }));
        return doc;
      },

      renameDoc: async (id, name) => {
        const trimmed = name.trim();
        if (!trimmed) throw new Error("文档名不能为空");
        // 以服务端返回为准(服务端会更新 updated_at 并校验存在性)
        const updated = await renameDocApi(id, trimmed);
        set((state) => ({
          docs: state.docs.map((d) => (d.id === id ? updated : d)),
        }));
      },

      removeDoc: async (id) => {
        await deleteDocApi(id);
        set((state) => ({ docs: state.docs.filter((d) => d.id !== id) }));
      },

      removeDocs: async (ids) => {
        if (!ids.length) return 0;
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
      },
    }),
    { name: "knowledge-docs" }
  )
);
