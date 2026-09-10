import { requestJson, USE_BACKEND } from "@/lib/api/client";

/** 工作区文件条目(后端相对路径)。 */
export interface WorkspaceEntry {
  path: string;
  directory: boolean;
  size: number;
  modifiedAt: string;
}

export interface WorkspaceStats {
  root: string;
  files: number;
  bytes: number;
  checkedAt: string;
}

/**
 * Agent 工作区接入层(USE_BACKEND 开关):
 * - getStats    → GET    /api/workspace
 * - listFiles   → GET    /api/workspace/files?dir=
 * - readFile    → GET    /api/workspace/file?path=
 * - writeFile   → PUT    /api/workspace/file
 * - deleteFile  → DELETE /api/workspace/file?path=
 */
export const workspaceApi = {
  async getStats(): Promise<WorkspaceStats | null> {
    if (!USE_BACKEND) return null;
    return requestJson<WorkspaceStats>("/workspace");
  },
  async listFiles(dir?: string): Promise<WorkspaceEntry[]> {
    if (!USE_BACKEND) return [];
    const q = dir ? `?dir=${encodeURIComponent(dir)}` : "";
    return requestJson<WorkspaceEntry[]>(`/workspace/files${q}`);
  },
  async readFile(path: string): Promise<string> {
    const row = await requestJson<{ path: string; content: string }>(
      `/workspace/file?path=${encodeURIComponent(path)}`
    );
    return row.content;
  },
  async writeFile(path: string, content: string): Promise<void> {
    await requestJson<{ path: string; content: string }>("/workspace/file", {
      method: "PUT",
      body: JSON.stringify({ path, content }),
    });
  },
  async deleteFile(path: string): Promise<void> {
    await requestJson<null>(`/workspace/file?path=${encodeURIComponent(path)}`, { method: "DELETE" });
  },
};
