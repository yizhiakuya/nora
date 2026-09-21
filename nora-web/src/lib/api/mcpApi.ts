import { requestJson, USE_BACKEND, defaultTimeoutSignal } from "./client";

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
  /** 工具加载策略:eager=工具直接挂载(每轮注入) / lazy=按需(agent 经 tools/call 使用,省上下文) */
  toolPolicy: "eager" | "lazy";
}

export interface McpToolInfo {
  name: string;
  description: string | null;
}

/** 工具详情(带完整 inputSchema;来自 tools_cache 快照,不触发远端调用) */
export interface McpToolDetail {
  name: string;
  description: string | null;
  /** JSON Schema(inputSchema),未提供时为 null */
  inputSchema: Record<string, unknown> | null;
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

/** 读取某服务器缓存的工具清单(名称/描述/参数 Schema);未测试过时为空数组 */
export async function fetchMcpTools(id: number): Promise<McpToolDetail[]> {
  if (!USE_BACKEND) return [];
  const result = await requestJson<{ tools: McpToolDetail[] }>(`/mcp/servers/${id}/tools`);
  return result.tools ?? [];
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

/** 设置工具加载策略(2026-09-18 P2-9):lazy=不挂载,按需经 agent tools/call 使用。 */
export async function setMcpServerToolPolicy(id: number, toolPolicy: "eager" | "lazy"): Promise<void> {
  if (!USE_BACKEND) return;
  await requestJson(`/mcp/servers/${id}/tool-policy`, {
    method: "PUT",
    body: JSON.stringify({ toolPolicy }),
  });
}

export async function refreshMcpServer(id: number): Promise<McpRefreshResult> {
  if (!USE_BACKEND) return { status: "connected", error: null, tools: [] };
  // refresh 会真实连远端:STDIO 首次 npx 下载放宽 120s(见 McpClientPool)、
  // 远程服务器慢时也可能超过默认 30s——前端必须给足窗口,否则先断流报错,
  // 而后端连接其实成功并已缓存工具清单(2026-09-21 修超时错配)。
  return requestJson(`/mcp/servers/${id}/refresh`, {
    method: "POST",
    signal: defaultTimeoutSignal(3 * 60_000),
  });
}

// ---------- GitHub OAuth(设备码流程) ----------

/** 登录能力状态:client_id 是否已配置 + 当前 github 服务器概况 */
export interface GitHubOAuthStatus {
  clientIdConfigured: boolean;
  serverName: string | null;
  serverTransport: string | null;
  serverStatus: string | null;
  toolCount: number | null;
}

/** 设备码授权发起结果:展示 userCode 让用户在验证页输入 */
export interface GitHubOAuthStart {
  flowId: string;
  userCode: string;
  verificationUri: string;
  expiresIn: number;
  interval: number;
}

/** 轮询结果;status: pending/slow_down/complete/expired/denied/error */
export interface GitHubOAuthPoll {
  status: "pending" | "slow_down" | "complete" | "expired" | "denied" | "error";
  message: string | null;
  serverName: string | null;
  toolCount: number | null;
  warning: string | null;
}

export async function fetchGitHubOAuthStatus(): Promise<GitHubOAuthStatus> {
  if (!USE_BACKEND) {
    return { clientIdConfigured: false, serverName: null, serverTransport: null, serverStatus: null, toolCount: null };
  }
  return requestJson("/mcp/oauth/github/status");
}

export async function saveGitHubClientId(clientId: string): Promise<GitHubOAuthStatus> {
  return requestJson("/mcp/oauth/github/client-id", {
    method: "PUT",
    body: JSON.stringify({ clientId }),
  });
}

export async function clearGitHubClientId(): Promise<GitHubOAuthStatus> {
  return requestJson("/mcp/oauth/github/client-id", { method: "DELETE" });
}

export async function startGitHubOAuth(): Promise<GitHubOAuthStart> {
  return requestJson("/mcp/oauth/github/start", { method: "POST" });
}

export async function pollGitHubOAuth(flowId: string): Promise<GitHubOAuthPoll> {
  return requestJson("/mcp/oauth/github/poll", {
    method: "POST",
    body: JSON.stringify({ flowId }),
  });
}
