import { withAuthToken } from "@/lib/auth";

/**
 * 产物画廊协议 v4(2026-09-18,按业务分画廊)。
 *
 * 设计演进:
 * - v1:AI 自写 H5——质量不可控,废弃;
 * - v2/v3:单一通用画廊/单组件多形态——越做越大,一坨 if-else,废弃;
 * - v4:**每种业务一个画廊**。协议里声明用哪个画廊,前端注册表分发到
 *   对应的独立组件(见 components/chat/galleries/)。每个画廊自己定义
 *   字段与样式,互不影响;新增业务 = 加一个小组件 + 注册一行。
 *
 * 围栏(```nora-artifacts),协议 = 画廊名 + 该画廊自己的数据:
 *   {"gallery": "media",  ...media 画廊的字段...}
 *   {"gallery": "files",  ...files 画廊的字段...}
 *   {"gallery": "table",  ...table 画廊的字段...}
 *   ...
 * 未知 gallery 名 → 降级通用列表(不报错,前向兼容)。
 *
 * 宽容归一化(2026-09-20 用户反馈:模型没按协议写时整段 JSON 原样显示):
 * 模型偶尔凭记忆写围栏——缺 gallery 字段、item 用 title/subtitle/path/type
 * 这类别名。解析层按形态**推断画廊类型并映射字段别名**,而不是直接降级成
 * 原始 JSON(实测踩过:相册整理任务输出一坨 JSON,用户以为坏了)。
 *
 * 画廊目录(字段定义见各自组件文件):
 *   media    相册/媒体     items[{url,thumbUrl,fullUrl,caption,meta,kind,name}]
 *   files    文件变更      items[{name,note,meta,open,kind}]
 *   table    数据表格      columns[{key,label,align?}] + rows[{key:value}]
 *   diff     设置变更      items[{name,before,after,caption,meta}]
 *   timeline 任务执行      items[{name,status,meta,caption}]
 *   keyvalue 单对象详情    items[{name,meta,open?}]
 *   text     长文报告      text
 *   list     通用兜底      items[{name,caption,meta,open}]
 *
 * 所有画廊共享的可选头部字段:title(必填)/ summary / stats[{label,value}] / note。
 *
 * open 深链前缀(openArtifactLink 统一处理):
 *   workspace:<相对路径>   → 文件页的工作区浏览器定位
 *   file:<数字id>          → 文件页打开文件中心预览
 *   url:<链接>             → 新窗口打开
 *   省略/未知前缀          → 仅展示不可点击(降级安全)
 */

/** 一条画廊实例:画廊名 + 该画廊自己的数据(结构由对应组件定义)。 */
export interface ArtifactGallery {
  gallery: string;
  data: Record<string, unknown>;
}

/** 所有画廊共享的头部字段(各组件自行取用)。 */
export interface GalleryHeaderFields {
  title?: string;
  summary?: string;
  stats?: Array<{ label: string; value: string }>;
  note?: string;
}

// ---------- 围栏解析 ----------

/**
 * 从文本里提取指定名字的顶层围栏内容(逐行状态机,跳过 4+ 反引号包裹的示例块)。
 *
 * 为什么不用简单正则:技能正文/文档里常用 ````text ... ```` 包裹示例围栏,
 * 简单正则会匹配到内层 3 反引号围栏,把「示例」当「真实数据」渲染。
 * 实测踩过:agent 读技能后,技能里的示例 JSON 被渲染成真画廊。
 */
function extractTopLevelFence(content: string, fenceName: string): string | null {
  const lines = content.split("\n");
  let outerFenceLen = 0; // >0 = 在 4+ 反引号的示例块内
  const fenceHead = new RegExp("^\\s*```\\s*" + fenceName + "\\s*$");
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
    if (fenceHead.test(line)) {
      const body: string[] = [];
      let closed = false;
      for (let j = i + 1; j < lines.length; j++) {
        if (/^\s*```\s*$/.test(lines[j])) {
          closed = true;
          i = j;
          break;
        }
        body.push(lines[j]);
      }
      if (closed) {
        return body.join("\n");
      }
      continue;
    }
    // 其他语言的普通围栏:跳过其内容到闭栏
    for (let j = i + 1; j < lines.length; j++) {
      if (/^\s*```\s*$/.test(lines[j])) {
        i = j;
        break;
      }
    }
  }
  return null;
}

/** 从文本里提取 ```nora-artifacts 围栏(无则 null)。 */
export function parseArtifactsFence(content: string | null | undefined): ArtifactGallery[] | null {
  if (!content) return null;
  const body = extractTopLevelFence(content, "nora-artifacts");
  return body != null ? parseArtifactsJson(body) : null;
}

/** 解析围栏 JSON;无效返回 null(调用方降级为普通代码块,不丢内容)。 */
export function parseArtifactsJson(raw: string): ArtifactGallery[] | null {
  try {
    const data = JSON.parse(raw) as Record<string, unknown>;
    if (!data || typeof data !== "object" || Array.isArray(data)) {
      return null;
    }
    // 新协议(v4):{gallery: "...", ...}
    if (typeof data.gallery === "string" && data.gallery.trim() !== "") {
      return [{ gallery: normalizeGalleryName(data.gallery.trim()), data: normalizeGalleryData(data.gallery.trim(), data) }];
    }
    // 兼容 v2/v3(历史消息):{sections: [{kind, ...}]} → 每段转一条画廊
    if (Array.isArray(data.sections)) {
      const out: ArtifactGallery[] = [];
      (data.sections as Array<Record<string, unknown>>).forEach((s, idx) => {
        if (!s || typeof s !== "object") return;
        const kind = typeof s.kind === "string" && s.kind.trim() !== "" ? s.kind.trim() : "list";
        // 首个 section 承接顶层 summary/stats(旧格式把它们放在顶层)
        const merged: Record<string, unknown> = { ...s };
        if (idx === 0) {
          if (data.summary != null && merged.summary == null) merged.summary = data.summary;
          if (data.stats != null && merged.stats == null) merged.stats = data.stats;
        }
        if (merged.title == null && data.title != null) merged.title = data.title;
        if (merged.note == null && data.note != null) merged.note = data.note;
        out.push({ gallery: normalizeGalleryName(kind), data: normalizeGalleryData(kind, merged) });
      });
      return out.length > 0 ? out : null;
    }
    // 宽容路径(2026-09-20 用户反馈):模型凭记忆写围栏——缺 gallery 字段、
    // item 用别名(title/subtitle/path/type)。这里按形态推断画廊类型并归一化,
    // 而不是整段丢弃让用户看到原始 JSON。
    const inferred = inferGallery(data);
    if (inferred) {
      return [inferred];
    }
    return null;
  } catch {
    return null;
  }
}

// ---------- 宽容归一化(缺字段/写别名时尽力渲染,不影响展示) ----------

/** 画廊名别名归一化(模型写 singular/大小写/同义词时映射到注册名)。 */
function normalizeGalleryName(name: string): string {
  const n = name.trim().toLowerCase();
  const alias: Record<string, string> = {
    photo: "media", photos: "media", image: "media", images: "media", album: "media",
    video: "media", videos: "media", gallery: "media",
    file: "files", folder: "files", folders: "files",
    grid: "table", rows: "table",
    changes: "diff", change: "diff",
    steps: "timeline", progress: "timeline", events: "timeline",
    detail: "keyvalue", details: "keyvalue", kv: "keyvalue", object: "keyvalue",
    report: "text", markdown: "text", md: "text",
    items: "list", default: "list",
  };
  return alias[n] ?? n;
}

/**
 * 按 JSON 形态推断画廊类型(缺 gallery 字段时)并归一化数据。
 * 优先级:有 columns+rows → table;items 里有图片 URL 形态 → media;
 * 有 text → text;items 有 open/note → files;其余有 items → list。
 */
function inferGallery(data: Record<string, unknown>): ArtifactGallery | null {
  if (Array.isArray(data.columns) && Array.isArray(data.rows)) {
    return { gallery: "table", data };
  }
  if (typeof data.text === "string" && data.text.trim() !== "") {
    return { gallery: "text", data };
  }
  const items = Array.isArray(data.items) ? (data.items as Array<Record<string, unknown>>) : null;
  if (items && items.length > 0) {
    const looksMedia = items.some((it) => it && typeof it === "object"
      && (typeof it.url === "string" || typeof it.fullUrl === "string" || typeof it.thumbUrl === "string"
          || typeof it.path === "string" || typeof it.src === "string")
      && (typeof it.type === "string" && /image|video|photo/i.test(it.type)
          || typeof it.kind === "string" && /image|video/i.test(it.kind)
          || typeof it.path === "string" && /\.(jpe?g|png|gif|webp|bmp|mp4|mov|webm)$/i.test(it.path)
          || typeof it.url === "string" && /\.(jpe?g|png|gif|webp|bmp|mp4|mov|webm)(\?|$)/i.test(it.url)
          || typeof it.subtitle === "string" && /×|MB|KB|GB/i.test(it.subtitle)));
    if (looksMedia) {
      return { gallery: "media", data: normalizeGalleryData("media", data) };
    }
    return { gallery: "list", data: normalizeGalleryData("list", data) };
  }
  // 连 items 都没有:无 title 也无可展示内容 → 不是画廊
  if (data.title == null && data.summary == null) {
    return null;
  }
  return { gallery: "list", data };
}

/**
 * 数据字段别名归一化(模型写的别名映射到组件字段;原字段保留)。
 * 全部字段都是可选的——缺任何字段都不该影响展示(2026-09-20 用户明确)。
 */
function normalizeGalleryData(gallery: string, data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...data };
  // 头部别名:heading/name → title;description → summary;footer → note
  if (out.title == null) out.title = out.heading ?? out.name;
  if (out.summary == null) out.summary = out.description;
  if (out.note == null) out.note = out.footer;
  const items = Array.isArray(out.items) ? out.items : null;
  if (!items) return out;
  out.items = items.map((raw) => {
    if (!raw || typeof raw !== "object") return raw;
    const it = { ...(raw as Record<string, unknown>) };
    // 通用别名(全部可选,缺啥都不影响展示)
    if (it.meta == null) {
      // media 的 subtitle 通常是尺寸/大小(如「3264×2448 · 3.0MB」)→ meta;
      // 其余画廊 subtitle 是说明文字 → caption
      it.meta = gallery === "media" ? (it.subtitle ?? it.size ?? it.detail) : (it.size ?? it.detail);
    }
    if (it.caption == null && gallery !== "media") it.caption = it.subtitle ?? it.label ?? it.desc;
    if (it.caption == null && gallery === "media") it.caption = it.label ?? it.desc;
    if (it.name == null) it.name = it.title ?? it.filename;
    // media 专属:path → url;src → url;thumbnail → thumbUrl;takenAt → meta 兜底
    if (it.url == null && typeof it.path === "string") {
      // 工作区相对路径 → raw 端点(可加载,附令牌供 <img> 使用);http(s) 原样
      const p = it.path as string;
      it.url = /^https?:\/\//i.test(p)
        ? p
        : withAuthToken(`/api/workspace/file/raw?path=${encodeURIComponent(p)}`);
    }
    if (it.url == null && typeof it.src === "string") it.url = it.src;
    if (it.thumbUrl == null) it.thumbUrl = it.thumbnail ?? it.thumb;
    if (it.kind == null) {
      const t = typeof it.type === "string" ? it.type.toLowerCase() : "";
      if (/video|mp4|mov|webm/.test(t) || typeof it.path === "string" && /\.(mp4|mov|webm)$/i.test(it.path as string)) {
        it.kind = "video";
      } else if (/image|photo|jpe?g|png|gif|webp/.test(t) || typeof it.path === "string" && /\.(jpe?g|png|gif|webp|bmp)$/i.test(it.path as string)) {
        it.kind = "image";
      }
    }
    // files 专属:path → open(workspace 深链)
    if (it.open == null && typeof it.path === "string" && gallery === "files") {
      it.open = `workspace:${it.path}`;
    }
    return it;
  });
  return out;
}

/**
 * 旧格式兼容:```nora-gallery(手机相册 photos_showcase 早期专属围栏)。
 * 转换为 media 画廊(历史消息/老 App 版本自动升级为原生体验)。
 */
export function parseLegacyGalleryFence(content: string | null | undefined): ArtifactGallery[] | null {
  if (!content) return null;
  const body = extractTopLevelFence(content, "nora-gallery");
  if (body == null) return null;
  try {
    const legacy = JSON.parse(body) as {
      title?: string;
      count?: number;
      items?: Array<{
        url?: string;
        fullUrl?: string;
        filename?: string;
        caption?: string;
        takenAt?: string;
        type?: string;
      }>;
      note?: string;
    };
    if (!legacy || !Array.isArray(legacy.items) || legacy.items.length === 0) {
      return null;
    }
    return [{
      gallery: "media",
      data: {
        title: typeof legacy.title === "string" ? legacy.title : "媒体画廊",
        summary: legacy.count != null ? `共 ${legacy.count} 项` : undefined,
        items: legacy.items.map((it) => ({
          kind: it.type === "video" ? "video" : "image",
          // media 画廊约定:url=灯箱用(原图优先), thumbUrl=网格用(缩略图)
          url: it.fullUrl ?? it.url ?? "",
          thumbUrl: it.url,
          name: it.filename,
          caption: it.caption ?? it.filename,
          meta: it.takenAt,
        })),
        note: legacy.note,
      },
    }];
  } catch {
    return null;
  }
}

// ---------- 共享工具 ----------

/** 解析 open 深链 → { scheme, target }。 */
export function parseOpen(open: string | undefined): { scheme: string; target: string } | null {
  if (!open) return null;
  const i = open.indexOf(":");
  if (i <= 0) return null;
  return { scheme: open.slice(0, i), target: open.slice(i + 1) };
}

/** 打开深链(workspace:/file:/url:);未知前缀静默忽略。 */
export function openArtifactLink(open: string | undefined): void {
  const parsed = parseOpen(open);
  if (!parsed) return;
  if (parsed.scheme === "workspace") {
    window.location.href = `/files?workspace=${encodeURIComponent(parsed.target)}`;
  } else if (parsed.scheme === "file") {
    window.location.href = `/files?open=${encodeURIComponent(parsed.target)}`;
  } else if (parsed.scheme === "url") {
    // 仅允许 http(s):画廊数据来自 agent 输出(可能被工具结果/文件内容影响),
    // url:javascript:/url:data: 经 window.open 会在新窗口执行脚本
    // (React 只对 href 属性净化,window.open 不受保护——2026-09-19 审查修复)
    if (/^https?:\/\//i.test(parsed.target)) {
      window.open(parsed.target, "_blank", "noopener");
    }
  }
}
