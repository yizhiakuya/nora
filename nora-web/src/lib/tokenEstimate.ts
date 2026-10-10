/** 可见文本的 token 估算：CJK 约 1/字，ASCII 约 1/4，其余约 1/2。 */
// ponytail: 字符权重只作估算；需要精确实时计数时接入对应模型的 tokenizer。
export function estimateTextTokens(text: string): number {
  let cjk = 0, ascii = 0, other = 0;
  for (const ch of text) {
    const c = ch.codePointAt(0) ?? 0;
    if (
      (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf) ||
      (c >= 0x3040 && c <= 0x30ff) || (c >= 0xac00 && c <= 0xd7af) ||
      (c >= 0x3000 && c <= 0x303f) || (c >= 0xff00 && c <= 0xffef)
    ) cjk++;
    else if (c < 0x80) ascii++;
    else other++;
  }
  return cjk + Math.floor(other / 2) + Math.floor(ascii / 4);
}

export function formatTokenCount(tokens: number): string {
  return tokens >= 1000 ? `${(tokens / 1000).toLocaleString("en-US", { maximumFractionDigits: 1 })}K` : String(tokens);
}
