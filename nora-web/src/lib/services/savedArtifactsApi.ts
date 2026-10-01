import { requestJson } from "@/lib/api/client";
/**
 * 对话保存成果登记(B1,2026-09-27)。
 *
 * 服务端权威记录「哪个文件/文档来自哪次对话」——此前只有浏览器
 * localStorage 记「已保存」状态,换浏览器就丢失,也无法从资料页找到
 * 「这个文件来自哪次对话」。内容仍在工作区/知识库,这里只存归属关系。
 */
export interface SavedArtifact {
  id: number;
  /** workspace_file(工作区文件)/ knowledge_doc(知识库文档) */
  kind: "workspace_file" | "knowledge_doc";
  /** workspace_file=工作区相对路径;knowledge_doc=知识文档 id */
  path: string;
  name: string;
  /** 来源会话 id(回到来源会话用) */
  sessionId: string | null;
  /** 来源消息键(前端 contentKey) */
  messageKey: string | null;
  createdAt: string;
}

export const savedArtifactsApi = {
  /** 登记一次保存(kind+path 幂等 upsert);失败静默返回 null(保存动作本身已成功,登记是增益)。 */
  async register(input: {
    kind: "workspace_file" | "knowledge_doc";
    path: string;
    name: string;
    sessionId?: string;
    messageKey?: string;
  }): Promise<SavedArtifact | null> {
    try {
      return await requestJson<SavedArtifact>("/saved-artifacts", {
        method: "POST",
        body: JSON.stringify(input),
      });
    } catch {
      return null;
    }
  },

  /** 列表(最新在前)。 */
  async list(limit = 50): Promise<SavedArtifact[]> {
    return requestJson<SavedArtifact[]>(`/saved-artifacts?limit=${limit}`);
  },

  /** 删除登记(不删实际文件)。 */
  async remove(id: number): Promise<void> {
    await requestJson<void>(`/saved-artifacts?id=${id}`, { method: "DELETE" });
  },
};
