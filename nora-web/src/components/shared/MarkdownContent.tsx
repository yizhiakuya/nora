import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { GalleryBlock, parseGalleryJson } from "@/components/chat/GalleryBlock";

/**
 * 把文本按 ```nora-gallery 围栏切成片段：围栏内是 photos_showcase 的
 * 结构化画廊数据（渲染成卡片），其余按 Markdown 渲染。
 *
 * 不走 react-markdown 的 components.code 覆盖：那样画廊会被外层 <pre>
 * 包住（代码块样式），切分后画廊直接是块级元素，样式干净。
 */
function splitGalleryFences(text: string): Array<{ type: "md"; text: string } | { type: "gallery"; raw: string }> {
  const out: Array<{ type: "md"; text: string } | { type: "gallery"; raw: string }> = [];
  const re = /```nora-gallery\s*\n([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push({ type: "md", text: text.slice(last, m.index) });
    out.push({ type: "gallery", raw: m[1] });
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push({ type: "md", text: text.slice(last) });
  return out;
}

export default function MarkdownContent({
  children,
  className,
}: {
  children: string;
  className?: string;
}) {
  const segments = splitGalleryFences(children);
  // 常见路径：无画廊围栏 → 单段 Markdown（保持原有行为）
  if (segments.length <= 1) {
    return (
      <div className={className}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
      </div>
    );
  }
  return (
    <div className={className}>
      {segments.map((seg, i) => {
        if (seg.type === "gallery") {
          const data = parseGalleryJson(seg.raw);
          // 坏数据退回原始围栏文本（不丢内容）
          return data ? (
            <GalleryBlock key={i} data={data} />
          ) : (
            <pre key={i} className="whitespace-pre-wrap break-words rounded-md bg-muted/60 px-2 py-1.5 text-[11px] font-mono">
              {seg.raw}
            </pre>
          );
        }
        return seg.text.trim() ? (
          <ReactMarkdown key={i} remarkPlugins={[remarkGfm]}>
            {seg.text}
          </ReactMarkdown>
        ) : null;
      })}
    </div>
  );
}
