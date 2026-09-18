import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useState } from "react";
import { ArtifactsBlock } from "@/components/chat/galleries";
import { parseArtifactsJson, parseLegacyGalleryFence } from "@/lib/artifacts";
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
 * 把文本按 ```nora-artifacts / ```nora-gallery(旧格式)围栏切成片段：
 * 围栏内是结构化画廊数据，统一由 ArtifactsBlock 原生渲染（旧格式自动转换）。
 *
 * 逐行状态机(而非简单正则):必须跳过被 4+ 反引号包裹的示例块——技能正文/
 * 文档里常用 ````text ... ```` 包裹示例围栏,简单正则会误匹配内层围栏,
 * 把示例数据当真实画廊渲染(实测踩过:技能里的示例 JSON 被渲染成画廊)。
 *
 * 不走 react-markdown 的 components.code 覆盖：那样画廊会被外层 <pre>
 * 包住（代码块样式），切分后画廊直接是块级元素，样式干净。
 */
type FenceSegment =
  | { type: "md"; text: string }
  | { type: "artifacts"; raw: string }
  | { type: "legacy-gallery"; raw: string };

function splitGalleryFences(text: string): FenceSegment[] {
  const out: FenceSegment[] = [];
  const lines = text.split("\n");
  let outerFenceLen = 0; // >0 = 在 4+ 反引号的示例块内
  let mdStart = 0; // 当前 md 片段起始行
  let mdBuf: string[] = [];

  const flushMd = (upToLine: number) => {
    // 收集 mdStart..upToLine-1 的行为 md 片段
    if (upToLine > mdStart) {
      mdBuf.push(...lines.slice(mdStart, upToLine));
    }
    if (mdBuf.length > 0) {
      const t = mdBuf.join("\n");
      if (t.trim()) out.push({ type: "md", text: t });
      mdBuf = [];
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fenceMatch = line.match(/^\s*(`{3,})/);
    if (!fenceMatch) {
      continue;
    }
    const len = fenceMatch[1].length;
    if (outerFenceLen > 0) {
      if (len === outerFenceLen) {
        outerFenceLen = 0;
      }
      continue;
    }
    if (len > 3) {
      outerFenceLen = len;
      continue;
    }
    const isArtifacts = /^\s*```\s*nora-artifacts\s*$/.test(line);
    const isGallery = /^\s*```\s*nora-gallery\s*$/.test(line);
    if (!isArtifacts && !isGallery) {
      continue; // 普通代码围栏:交给 Markdown 渲染
    }
    // 收集围栏内容直到闭栏
    const body: string[] = [];
    let closed = false;
    let endLine = i;
    for (let j = i + 1; j < lines.length; j++) {
      if (/^\s*```\s*$/.test(lines[j])) {
        closed = true;
        endLine = j;
        break;
      }
      body.push(lines[j]);
    }
    if (!closed) {
      continue;
    }
    flushMd(i);
    out.push({ type: isArtifacts ? "artifacts" : "legacy-gallery", raw: body.join("\n") });
    mdStart = endLine + 1;
    i = endLine;
  }
  flushMd(lines.length);
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
        if (seg.type === "artifacts" || seg.type === "legacy-gallery") {
          // 产物画廊:新协议直接解析(可含多条画廊实例);旧 nora-gallery 转换为
          // media 画廊(历史消息兼容)。坏数据退回原始围栏文本(不丢内容)。
          const galleries = seg.type === "artifacts"
            ? parseArtifactsJson(seg.raw)
            : parseLegacyGalleryFence("```nora-gallery\n" + seg.raw + "\n```");
          return galleries && galleries.length > 0 ? (
            <div key={i} className="space-y-1.5">
              {galleries.map((g, gi) => (
                <ArtifactsBlock key={gi} gallery={g} />
              ))}
            </div>
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
