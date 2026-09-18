/**
 * 通用产物画廊协议(2026-09-18 v2,对齐手机 MCP 画廊的原生体验)。
 *
 * 设计动机(用户反馈 v1 H5 方案的问题):
 * - AI 手写 HTML 质量不可控、风格不统一、没有 Nora 原生交互(灯箱/打开文件);
 * - 手机相册画廊(```nora-gallery)的「结构化数据 + 原生组件渲染」体验才是目标。
 *
 * 本协议把那条路线通用化:AI 只声明**产出了什么**(JSON),前端用原生组件渲染
 * ——媒体走 ImageLightbox 灯箱、文件点击打开文件页/工作区,风格与 Nora 一体。
 *
 * 围栏:
 *   ```nora-artifacts
 *   {
 *     "title": "相册整理完成",
 *     "summary": "28 个视频归档到 photos/2026-09-16-show",
 *     "stats": [{"label": "文件", "value": "28"}, {"label": "大小", "value": "409.7 MB"}],
 *     "sections": [
 *       {"kind": "media", "title": "已归档", "items": [
 *         {"kind": "image", "url": "https://... 或 /api/workspace/file/raw?path=...",
 *          "fullUrl": "可选原图", "caption": "VID_xxx", "meta": "412 MB"}
 *       ]},
 *       {"kind": "files", "title": "变更", "items": [
 *         {"kind": "file", "name": "MEMORY.md", "note": "已更新", "open": "workspace:MEMORY.md"}
 *       ]}
 *     ],
 *     "note": "源目录已清空"
 *   }
 *   ```
 *
 * open 深链前缀(前端路由):
 *   workspace:<相对路径>   → 文件页的工作区浏览器定位
 *   file:<数字id>          → 文件页打开文件中心预览
 *   url:<链接>             → 新窗口打开
 *   省略/未知前缀          → 仅展示不可点击(降级安全)
 */

export type ArtifactItemKind = "image" | "video" | "file" | "folder" | "link" | "text";

export interface ArtifactItem {
  kind?: ArtifactItemKind;
  /** 展示名(文件名/条目名) */
  name?: string;
  /** 媒体地址(http(s) 或 /api/... 相对路径) */
  url?: string;
  /** 媒体原图/原片地址(可选;省略用 url) */
  fullUrl?: string;
  /** 缩略图地址(可选;媒体网格用) */
  thumbUrl?: string;
  /** 单条说明(agent 填写) */
  caption?: string;
  /** 元信息(大小/时间/数量等,一行) */
  meta?: string;
  /** 深链打开指令(见文件头注释);省略=仅展示 */
  open?: string;
}

export interface ArtifactSection {
  /** media=媒体网格(灯箱) / files=文件列表(点击打开) / list=通用条目列表 */
  kind?: "media" | "files" | "list";
  title?: string;
  items: ArtifactItem[];
}

export interface ArtifactStat {
  label: string;
  value: string;
}

export interface ArtifactsData {
  title: string;
  /** 一句话摘要(标题下方) */
  summary?: string;
  /** 统计条(如 文件 28 / 大小 409.7 MB / 耗时 15s) */
  stats?: ArtifactStat[];
  /** 分组内容(至少一段) */
  sections: ArtifactSection[];
  /** 底部补充说明 */
  note?: string;
}

/** 从文本里提取 ```nora-artifacts 围栏(无则 null)。 */
export function parseArtifactsFence(content: string | null | undefined): ArtifactsData | null {
  if (!content) return null;
  const m = content.match(/```nora-artifacts\s*\n([\s\S]*?)```/);
  return m ? parseArtifactsJson(m[1]) : null;
}

/** 解析围栏 JSON;结构不完整返回 null(调用方降级为普通代码块,不丢内容)。 */
export function parseArtifactsJson(raw: string): ArtifactsData | null {
  try {
    const data = JSON.parse(raw) as ArtifactsData;
    if (!data || typeof data.title !== "string" || !Array.isArray(data.sections)) {
      return null;
    }
    // 过滤空段;全空则视为无效(避免渲染空壳)
    const sections = data.sections.filter((s) => s && Array.isArray(s.items) && s.items.length > 0);
    if (sections.length === 0) {
      return null;
    }
    return { ...data, sections };
  } catch {
    return null;
  }
}

/** 条目的展示类型推断(kind 缺省时按 url/扩展名猜)。 */
export function itemKind(item: ArtifactItem): ArtifactItemKind {
  if (item.kind) return item.kind;
  const u = (item.url ?? item.fullUrl ?? "").toLowerCase();
  if (/\.(mp4|mov|webm|avi|mkv)(\?|$)/.test(u)) return "video";
  if (/\.(jpg|jpeg|png|gif|webp|bmp|svg|heic)(\?|$)/.test(u)) return "image";
  if (item.open?.startsWith("file:")) return "file";
  return "file";
}

/** 解析 open 深链 → { scheme, target }。 */
export function parseOpen(open: string | undefined): { scheme: string; target: string } | null {
  if (!open) return null;
  const i = open.indexOf(":");
  if (i <= 0) return null;
  return { scheme: open.slice(0, i), target: open.slice(i + 1) };
}
