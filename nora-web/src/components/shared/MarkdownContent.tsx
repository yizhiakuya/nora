import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useState } from "react";
import { GalleryBlock, parseGalleryJson } from "@/components/chat/GalleryBlock";
import { ArtifactBlock } from "@/components/chat/ArtifactBlock";
import { parseArtifactJson } from "@/lib/artifact";
import { ImageLightbox, type LightboxImage } from "@/components/shared/ImageLightbox";

/**
 * Markdown 正文里的图片：点击页内灯箱放大（不再跳外部标签页）。
 * 单张图也给灯箱——行为一致（点击放大、Esc 关闭）。
 */
function MarkdownImage({ src, alt }: { src?: string; alt?: string }) {
  const [open, setOpen] = useState(false);
  if (!src) return null;
  const images: LightboxImage[] = [{ src, alt }];
  return (
    <>
      <img
        src={src}
        alt={alt ?? ""}
        loading="lazy"
        onClick={() => setOpen(true)}
        className="max-w-full rounded-lg border border-border cursor-zoom-in my-1.5"
      />
      {open && (
        <ImageLightbox
          images={images}
          index={0}
          onClose={() => setOpen(false)}
          onIndexChange={() => {}}
        />
      )}
    </>
  );
}

/**
 * 把文本按 ```nora-gallery / ```nora-artifact 围栏切成片段：围栏内是结构化
 * 数据（画廊卡片 / H5 产物沙箱），其余按 Markdown 渲染。
 *
 * 不走 react-markdown 的 components.code 覆盖：那样画廊会被外层 <pre>
 * 包住（代码块样式），切分后画廊直接是块级元素，样式干净。
 */
type FenceSegment =
  | { type: "md"; text: string }
  | { type: "gallery"; raw: string }
  | { type: "artifact"; raw: string };

function splitGalleryFences(text: string): FenceSegment[] {
  const out: FenceSegment[] = [];
  const re = /```(nora-gallery|nora-artifact)\s*\n([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push({ type: "md", text: text.slice(last, m.index) });
    out.push({ type: m[1] === "nora-artifact" ? "artifact" : "gallery", raw: m[2] });
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
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            img: ({ src, alt }) => <MarkdownImage src={typeof src === "string" ? src : undefined} alt={alt} />,
          }}
        >
          {children}
        </ReactMarkdown>
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
        if (seg.type === "artifact") {
          const data = parseArtifactJson(seg.raw);
          // 坏数据退回原始围栏文本（不丢内容）
          return data ? (
            <ArtifactBlock key={i} data={data} />
          ) : (
            <pre key={i} className="whitespace-pre-wrap break-words rounded-md bg-muted/60 px-2 py-1.5 text-[11px] font-mono">
              {seg.raw}
            </pre>
          );
        }
        return seg.text.trim() ? (
          <ReactMarkdown
            key={i}
            remarkPlugins={[remarkGfm]}
            components={{
              img: ({ src, alt }) => <MarkdownImage src={typeof src === "string" ? src : undefined} alt={alt} />,
            }}
          >
            {seg.text}
          </ReactMarkdown>
        ) : null;
      })}
    </div>
  );
}
