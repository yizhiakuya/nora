import ReactMarkdown, { defaultUrlTransform } from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArtifactsBlock } from "@/components/chat/galleries";
import { parseArtifactsJson, parseLegacyGalleryFence } from "@/lib/artifacts";
import { useFileViewer } from "@/hooks/useFileViewer";
import { viewerApi, viewerTargetFromUrl } from "@/lib/services/viewerApi";
import { mediaCacheUrl, originalVariant } from "@/lib/mediaCache";

/**
 * 图片与文件链接进入共享查看器；工作区 Markdown 支持相对资源。
 */
function MarkdownImage({ src, alt, fileTarget, sessionId }: { src?: string; alt?: string; fileTarget?: string; sessionId?: string }) {
  if (!src) return null;
  const target = markdownFileTarget(src, fileTarget);
  const url = target ? viewerApi.rawUrl({ target, version: "" }) : mediaCacheUrl(src);
  return (
    <>
      <button type="button" className="block max-w-full" aria-label={`查看图片 ${alt ?? ""}`} onClick={() => void useFileViewer.getState().openTargets([target ?? originalVariant(src)], undefined, { sessionId })}><img
        src={url}
        alt={alt ?? ""}
        loading="lazy"
        className="max-w-full rounded-lg border border-border cursor-zoom-in my-1.5"
      /></button>
    </>
  );
}

function markdownFileTarget(value: string, fileTarget?: string): string | null {
  const target = viewerTargetFromUrl(value);
  if (target) return target;
  if (!fileTarget?.startsWith("workspace:") || /^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(value)) return null;
  try { return `workspace:${decodeURIComponent(new URL(value, `https://workspace.invalid/${fileTarget.slice(10)}`).pathname.slice(1))}`; }
  catch { return null; }
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
      // 普通代码围栏:跳过其内容到闭栏——围栏内形如 ```nora-artifacts 的行
      // 只是示例文本,不能当真实画廊渲染(与 artifacts.ts extractTopLevelFence
      // 同款防护;实测踩过:技能示例被误渲染成真画廊)
      let closed = false;
      for (let j = i + 1; j < lines.length; j++) {
        if (/^\s*```\s*$/.test(lines[j])) {
          i = j;
          closed = true;
          break;
        }
      }
      if (!closed) i = lines.length; // 未闭合:其后全部是代码块内容
      continue;
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
  fileTarget,
  sessionId,
}: {
  children: string;
  className?: string;
  fileTarget?: string;
  sessionId?: string;
}) {
  const segments = splitGalleryFences(children);
  const components = {
    img: ({ src, alt }: { src?: string; alt?: string }) => <MarkdownImage src={src} alt={alt} fileTarget={fileTarget} sessionId={sessionId} />,
    a: ({ href, children }: { href?: string; children?: React.ReactNode }) => {
      const target = href ? markdownFileTarget(href, fileTarget) : null;
      return <a href={target ? `?viewer=${encodeURIComponent(target)}` : href} onClick={event => {
        if (!target || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        void useFileViewer.getState().openTargets([target], undefined, { sessionId });
      }}>{children}</a>;
    },
  };
  const urlTransform = (url: string) => /^(workspace:|file:|media:)/.test(url) ? url : defaultUrlTransform(url);
  // 常见路径：无画廊围栏 → 单段 Markdown（保持原有行为）
  if (segments.length === 0 || (segments.length === 1 && segments[0].type === "md")) {
    return (
      <div className={className}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={components} urlTransform={urlTransform}
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
                <ArtifactsBlock key={gi} gallery={g} sessionId={sessionId} />
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
            components={components} urlTransform={urlTransform}
          >
            {seg.text}
          </ReactMarkdown>
        ) : null;
      })}
    </div>
  );
}
