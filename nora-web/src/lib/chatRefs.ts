/**
 * 对话消息的引用条目(2026-09-17;2026-09-17 晚扩展技能/MCP 服务器):
 * 附件/文件/知识库文档/技能/MCP 服务器随消息发送。
 *
 * 设计:引用以固定格式的行追加在消息正文末尾——零后端契约改动,
 * 引用信息随 content 一起持久化(历史加载后仍可解析),agent 直接可读,
 * 前端渲染时再拆回 chip 展示(splitChatRefs 与 formatChatRefs 互逆)。
 * 后端 MessageRefResolver 解析同样的行格式并注入真实内容。
 */

export interface ChatRef {
  /** file=文件中心文件;doc=知识库文档;skill=指令型技能;mcp=MCP 服务器;datasource=数据源连接 */
  kind: "file" | "doc" | "skill" | "mcp" | "datasource";
  /** 实体 id(mcp 为 serverId;datasource 为连接 id) */
  id: number;
  /** 展示名(mcp 为服务器名;datasource 为连接名) */
  name: string;
  /** 文件大小(仅 file;展示用) */
  size?: string;
}

const FILE_REF_RE = /^\[引用文件\]\s*(.+?)\s*\(file_id=(\d+)(?:,\s*([^)]+))?\)/;
const DOC_REF_RE = /^\[引用知识库\]\s*(.+?)\s*\(doc_id=(\d+)\)/;
const SKILL_REF_RE = /^\[引用技能\]\s*(.+?)\s*\(skill_id=(\d+)\)/;
const MCP_REF_RE = /^\[引用MCP服务器\]\s*(.+?)\s*\(server_id=(\d+)\)/;
const DATASOURCE_REF_RE = /^\[引用数据源\]\s*(.+?)\s*\(connection_id=(\d+)\)/;

/**
 * 把待发送引用序列化为消息尾部的引用块(每行一条)。
 * 提示语面向 agent:后端解析这些行并把引用内容直接注入上下文
 * (见 agent-service MessageRefResolver),提示语说明注入语义与补读路径。
 */
export function formatChatRefs(refs: ChatRef[]): string {
  if (refs.length === 0) return "";
  return refs
    .map((r) => {
      switch (r.kind) {
        case "file":
          return `[引用文件] ${r.name} (file_id=${r.id}${r.size ? `, ${r.size}` : ""}) —— 内容已随消息提供;如需完整原文可用 read_file 工具读取`;
        case "doc":
          return `[引用知识库] ${r.name} (doc_id=${r.id}) —— 内容已注入上下文,回答时优先参考`;
        case "skill":
          return `[引用技能] ${r.name} (skill_id=${r.id}) —— 该技能指令已注入,请遵循其指令执行本任务`;
        case "mcp":
          // 中性文案:后端 MessageRefResolver.resolveMcp 会按服务器 tool_policy
          // (eager 挂载名 / lazy tools+call)生成具体指引——前端不硬编码挂载名,
          // 否则 lazy 服务器(工具未挂载)的引用行会误导模型
          return `[引用MCP服务器] ${r.name} (server_id=${r.id}) —— 用户要求优先使用该服务器提供的工具处理本请求`;
        case "datasource":
          return `[引用数据源] ${r.name} (connection_id=${r.id}) —— 本任务指定该数据源;查询时用 execute_sql 并指定 datasource=${r.name}`;
      }
    })
    .join("\n");
}

/** 从消息内容中拆出引用行与正文(渲染用户气泡用;解析失败的行原样归入正文)。 */
export function splitChatRefs(content: string): { body: string; refs: ChatRef[] } {
  const refs: ChatRef[] = [];
  const bodyLines: string[] = [];
  for (const raw of content.split("\n")) {
    const line = raw.trim();
    let m = FILE_REF_RE.exec(line);
    if (m) {
      refs.push({ kind: "file", id: Number(m[2]), name: m[1], size: m[3] });
      continue;
    }
    m = DOC_REF_RE.exec(line);
    if (m) {
      refs.push({ kind: "doc", id: Number(m[2]), name: m[1] });
      continue;
    }
    m = SKILL_REF_RE.exec(line);
    if (m) {
      refs.push({ kind: "skill", id: Number(m[2]), name: m[1] });
      continue;
    }
    m = MCP_REF_RE.exec(line);
    if (m) {
      refs.push({ kind: "mcp", id: Number(m[2]), name: m[1] });
      continue;
    }
    m = DATASOURCE_REF_RE.exec(line);
    if (m) {
      refs.push({ kind: "datasource", id: Number(m[2]), name: m[1] });
      continue;
    }
    bodyLines.push(raw);
  }
  // 引用块前的分隔空行一并清理(引用全在尾部时 body 不留尾随空行)
  return { body: bodyLines.join("\n").replace(/\n+$/, ""), refs };
}

/** 引用的唯一键(去重/移除/React key 共用)。 */
export function refKey(ref: ChatRef): string {
  return `${ref.kind}:${ref.id}`;
}
