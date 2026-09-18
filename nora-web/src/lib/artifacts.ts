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
    if (!data || typeof data !== "object") {
      return null;
    }
    // 新协议(v4):{gallery: "...", ...}
    if (typeof data.gallery === "string" && data.gallery.trim() !== "") {
      return [{ gallery: data.gallery.trim(), data }];
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
        out.push({ gallery: kind, data: merged });
      });
      return out.length > 0 ? out : null;
    }
    return null;
  } catch {
    return null;
  }
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
    window.open(parsed.target, "_blank", "noopener");
  }
}
