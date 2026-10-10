export const CHAT_COMMANDS = [
  { name: "help", description: "查看命令帮助", keywords: "帮助 命令" },
  { name: "compact", description: "压缩模型工作上下文，保留聊天记录", keywords: "压缩 整理 摘要", argument: "希望保留的重点" },
  { name: "context", description: "查看上下文用量与压缩检查点", keywords: "上下文 token 用量" },
  { name: "status", description: "查看当前会话与 Agent 状态", keywords: "状态 模型 权限" },
  { name: "new", description: "开始新对话，保留当前会话", keywords: "新建 对话" },
  { name: "tasks", description: "打开后台任务面板", keywords: "后台 任务 命令" },
  { name: "model", description: "选择后续请求使用的默认模型", keywords: "模型 渠道" },
  { name: "reasoning", description: "调整后续请求的默认思考等级", keywords: "思考 推理 等级" },
  { name: "permissions", description: "调整 Agent 默认权限", keywords: "权限 批准" },
  { name: "mcp", description: "管理 MCP 连接与工具", keywords: "连接 工具 安装" },
] as const;

export type ChatCommand = typeof CHAT_COMMANDS[number];
export type ChatCommandName = ChatCommand["name"];
export type ChatSettingsMenu = "model" | "reasoning" | "permissions";

/** 只识别整条输入的已知命令；路径、未知命令和普通正文继续作为消息发送。 */
export function parseChatCommand(input: string): { command: ChatCommand; argument: string } | null {
  const match = /^\/([a-z]+)(?:\s+([\s\S]*))?$/i.exec(input.trim());
  const command = match && CHAT_COMMANDS.find(item => item.name === match[1].toLowerCase());
  if (!command) return null;
  return { command, argument: match?.[2]?.trim() ?? "" };
}

export function matchingChatCommands(query: string): ChatCommand[] {
  const value = query.trim().toLowerCase();
  return CHAT_COMMANDS.filter(command => `${command.name} ${command.description} ${command.keywords}`.toLowerCase().includes(value));
}
