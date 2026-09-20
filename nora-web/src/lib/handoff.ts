/**
 * 跨页「交给助手」统一交接(M2-02,2026-09-20,方案 §4.1/§6.2)。
 *
 * 从文件、查询、日志等页面发起处理时,保留**稳定对象 ID**(而非仅自然语言),
 * 跳转到对话页并预填:指令文本 + 结构化引用(refs)。用户在输入区可增删引用、
 * 编辑指令后再发送(不自动发送)。
 *
 * URL 协议:/chat?prompt=<文本>&refs=<JSON 数组>
 *   refs 条目:{kind:"file"|"doc"|"skill"|"mcp", id:<number>, name:<string>}
 * chat 页解析后转为引用 chip;发送时随结构化 context 提交后端精确解析。
 */

export interface HandoffRef {
  kind: "file" | "doc" | "skill" | "mcp" | "datasource";
  id: number;
  name: string;
}

/** 构造「交给助手」跳转 URL(含可选引用集合)。 */
export function buildAssistantHandoffUrl(prompt: string, refs: HandoffRef[] = []): string {
  const params = new URLSearchParams();
  params.set("prompt", prompt);
  if (refs.length > 0) {
    params.set("refs", JSON.stringify(refs));
  }
  return `/chat?${params.toString()}`;
}
