import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { withAuthToken } from "@/lib/auth";

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
  /** 图片等二进制文件的预览 URL(原始字节端点,浏览器直接渲染)。 */
  rawUrl(path: string): string {
    // 图片 src 无法带 header:令牌走 ?token=
    return withAuthToken(`/api/workspace/file/raw?path=${encodeURIComponent(path)}`);
  },
  async writeFile(path: string, content: string, expectedHash?: string | null): Promise<void> {
    await requestJson<{ path: string; content: string }>("/workspace/file", {
      method: "PUT",
      body: JSON.stringify({ path, content, expectedHash }),
    });
  },
  async deleteFile(path: string): Promise<void> {
    await requestJson<null>(`/workspace/file?path=${encodeURIComponent(path)}`, { method: "DELETE" });
  },
};
