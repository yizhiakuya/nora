import { requestJson, USE_BACKEND } from "./client";

/** MCP 服务器视图(后端 ServerView;secrets 已脱敏) */
export interface McpServer {
  id: number;
  name: string;
  url: string | null;
  transport: "STREAMABLE" | "SSE" | "STDIO";
  /** STDIO 时:可执行命令(npx / node / docker ...) */
  command: string | null;
  /** STDIO 时:argv JSON 数组字符串 */
  args: string | null;
  maskedHeaders: string | null;
  /** STDIO 时:环境变量(值已脱敏) */
  maskedEnv: string | null;
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
  url?: string;
  transport: "STREAMABLE" | "SSE" | "STDIO";
  headers?: Record<string, string>;
  /** STDIO:可执行命令 */
  command?: string;
  /** STDIO:命令参数 */
  args?: string[];
  /** STDIO:环境变量 */
  env?: Record<string, string>;
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
