import { requestJson, USE_BACKEND } from "./client";

/** MCP 服务器视图(后端 ServerView;headers 已脱敏) */
export interface McpServer {
  id: number;
  name: string;
  url: string;
  transport: "STREAMABLE" | "SSE";
  maskedHeaders: string | null;
  enabled: boolean;
  status: "connected" | "error" | "untested";
  statusDetail: string | null;
  toolCount: number;
}

export interface McpToolInfo {
  name: string;
  description: string | null;
}

export interface McpRefreshResult {
  status: string;
  error: string | null;
  tools: McpToolInfo[] | null;
}

export async function fetchMcpServers(): Promise<McpServer[]> {
  if (!USE_BACKEND) return [];
  return requestJson("/mcp/servers");
}

export async function createMcpServer(input: {
  name: string;
  url: string;
  transport: "STREAMABLE" | "SSE";
  headers?: Record<string, string>;
}): Promise<McpServer> {
  return requestJson("/mcp/servers", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export async function deleteMcpServer(id: number): Promise<void> {
  if (!USE_BACKEND) return;
  await requestJson(`/mcp/servers/${id}`, { method: "DELETE" });
}

export async function setMcpServerEnabled(id: number, enabled: boolean): Promise<void> {
  if (!USE_BACKEND) return;
  await requestJson(`/mcp/servers/${id}/enabled`, {
    method: "PUT",
    body: JSON.stringify({ enabled }),
  });
}

export async function refreshMcpServer(id: number): Promise<McpRefreshResult> {
  if (!USE_BACKEND) return { status: "connected", error: null, tools: [] };
  return requestJson(`/mcp/servers/${id}/refresh`, { method: "POST" });
}
