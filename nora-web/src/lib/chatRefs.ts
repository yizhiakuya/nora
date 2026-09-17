/**
 * 对话消息的引用条目(2026-09-17):附件/文件/知识库文档随消息发送。
 *
 * 设计:引用以固定格式的行追加在消息正文末尾——零后端契约改动,
 * 引用信息随 content 一起持久化(历史加载后仍可解析),agent 直接可读,
 * 前端渲染时再拆回 chip 展示(splitChatRefs 与 formatChatRefs 互逆)。
 */

export interface ChatRef {
  /** file = 文件中心文件(file_id);doc = 知识库文档(doc_id) */
  kind: "file" | "doc";
  id: number;
  name: string;
  /** 文件大小(仅 file;展示用) */
  size?: string;
}

const FILE_REF_RE = /^\[引用文件\]\s*(.+?)\s*\(file_id=(\d+)(?:,\s*([^)]+))?\)/;
const DOC_REF_RE = /^\[引用知识库\]\s*(.+?)\s*\(doc_id=(\d+)\)/;

/**
 * 把待发送引用序列化为消息尾部的引用块(每行一条)。
 * 提示语面向 agent:后端解析这些行并把引用内容直接注入上下文
 * (见 agent-service MessageRefResolver),提示语说明注入语义与补读路径。
 */
export function formatChatRefs(refs: ChatRef[]): string {
  if (refs.length === 0) return "";
  return refs
    .map((r) =>
      r.kind === "file"
        ? `[引用文件] ${r.name} (file_id=${r.id}${r.size ? `, ${r.size}` : ""}) —— 内容已随消息提供;如需完整原文可用 read_file 工具读取`
        : `[引用知识库] ${r.name} (doc_id=${r.id}) —— 内容已注入上下文,回答时优先参考`
    )
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
    bodyLines.push(raw);
  }
  // 引用块前的分隔空行一并清理(引用全在尾部时 body 不留尾随空行)
  return { body: bodyLines.join("\n").replace(/\n+$/, ""), refs };
}
