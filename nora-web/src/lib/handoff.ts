/**
 * 跨页「交给助手」统一交接(M2-02,2026-09-20,方案 §4.1/§6.2)。
 *
 * 从文件、查询、日志等页面发起处理时,保留**稳定对象 ID**(而非仅自然语言),
 * 跳转到对话页并预填:指令文本 + 结构化引用(refs)。用户在输入区可增删引用、
 * 编辑指令后再发送(不自动发送)。
 *
 * URL 协议:/chat?prompt=<文本>&refs=<JSON 数组>[&new=1]
 *   refs 条目:{kind:"file"|"doc"|"skill"|"mcp", id:<number>, name:<string>}
 * chat 页解析后转为引用 chip;发送时随结构化 context 提交后端精确解析。
 *
 * B6(2026-09-27,评审报告):此前交接不指定会话——对话页用当前 active 会话,
 * 「从另一份资料发起新需求」可能接着旧讨论工作,界面没有表达这个选择。
 * 现在交接默认 **new=1(新建会话)**:从资料页发起的是一个新处理任务,
 * 语义明确;需要延续旧讨论的用户可在对话页内直接引用,或从会话列表进入。
 */

export interface HandoffRef {
  kind: "file" | "doc" | "skill" | "mcp" | "datasource";
  id: number;
  name: string;
}

/**
 * 构造「交给助手」跳转 URL(含可选引用集合)。
 *
 * @param newSession 是否新建会话(缺省 true——跨页发起 = 新处理任务;
 *                   传 false 保留旧行为「加入当前对话」)
 */
export function buildAssistantHandoffUrl(prompt: string, refs: HandoffRef[] = [],
                                          newSession = true): string {
  const params = new URLSearchParams();
  params.set("prompt", prompt);
  if (refs.length > 0) {
    params.set("refs", JSON.stringify(refs));
  }
  if (newSession) {
    params.set("new", "1");
  }
  return `/chat?${params.toString()}`;
}
