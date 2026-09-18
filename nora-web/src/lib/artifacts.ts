/**
 * 通用产物画廊协议(2026-09-18 v3,多形态)。
 *
 * 设计动机:
 * - v1(AI 自写 H5)质量不可控;v2(单一固定布局)表达力不足——不同业务需要
 *   不同样式与字段(相册=缩略图网格、SQL=表格、设置=改前改后对比、任务=时间线);
 * - v3 = **section 类型目录**:一套围栏协议,多种渲染器,AI 按业务选类型。
 *
 * 围栏(```nora-artifacts):
 *   {
 *     "title": "...", "summary": "...",
 *     "stats": [{"label": "文件", "value": "28"}],
 *     "sections": [ ...按需混用下列类型... ],
 *     "note": "..."
 *   }
 *
 * section 类型目录(渲染器在 ArtifactsBlock;未知类型降级 list,不报错):
 *   media    媒体网格(缩略图/灯箱)         items[{url,thumbUrl,caption,meta,kind}]
 *   files    文件行(点击打开)              items[{name,note,meta,open,kind}]
 *   table    数据表格(SQL/统计)            columns:[{key,label,align?}] + rows:[{key:value}]
 *   diff     改前→改后(设置/配置)          items[{name,before,after,open?}]
 *   timeline 时间线(任务/事件)             items[{name,status,meta,caption?}] status: done|failed|running|pending
 *   keyvalue 键值详情(单对象)              items[{name,meta}] name=键 meta=值
 *   text     长文报告                      text:"Markdown 文本"
 *   list     通用条目(兜底)                items[{name,caption,meta,open}]
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
  /** 展示名(文件名/条目名/键名) */
  name?: string;
  /** 媒体地址(http(s) 或 /api/... 相对路径) */
  url?: string;
  /** 媒体原图/原片地址(可选;省略用 url) */
  fullUrl?: string;
  /** 缩略图地址(可选;媒体网格用) */
  thumbUrl?: string;
  /** 单条说明(agent 填写) */
  caption?: string;
  /** 元信息(大小/时间/数量/值等,一行) */
  meta?: string;
  /** 深链打开指令(见文件头注释);省略=仅展示 */
  open?: string;
  /** diff 段:改前 */
  before?: string;
  /** diff 段:改后 */
  after?: string;
  /** timeline 段:done | failed | running | pending */
  status?: string;
}

/** table 段的列定义。 */
export interface ArtifactColumn {
  key: string;
  label: string;
  align?: "left" | "right";
}

export interface ArtifactSection {
  /**
   * media=媒体网格 / files=文件行 / table=数据表格 / diff=改前后对比 /
   * timeline=时间线 / keyvalue=键值详情 / text=长文 / list=通用兜底
   */
  kind?: "media" | "files" | "table" | "diff" | "timeline" | "keyvalue" | "text" | "list";
  title?: string;
  items?: ArtifactItem[];
  /** table 段:列定义(rows 用对象的 key 取值) */
  columns?: ArtifactColumn[];
  /** table 段:行数据 */
  rows?: Array<Record<string, unknown>>;
  /** text 段:Markdown 正文 */
  text?: string;
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

/**
 * 从文本里提取指定名字的围栏内容(逐行状态机,正确跳过 4+ 反引号包裹的示例块)。
 *
 * 为什么不用简单正则:技能正文/文档里常用 ````text ... ```` 包裹示例围栏,
 * 简单正则会匹配到内层 3 反引号围栏,把「示例」当「真实数据」渲染。
 * 实测踩过:agent 读技能后,技能里的示例 JSON 被渲染成真画廊。
 *
 * @param content 待扫描文本
 * @param fenceName 围栏名(如 nora-artifacts)
 * @return 第一个**顶层**围栏的内容;无则 null
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
      // 示例块内:仅同长度闭栏才退出(内部一切不解析)
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
      // 目标围栏:收集内容直到闭栏
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
    // 其他语言的普通围栏:跳过其内容到闭栏(避免内容里的行被误判)
    for (let j = i + 1; j < lines.length; j++) {
      if (/^\s*```\s*$/.test(lines[j])) {
        i = j;
        break;
      }
    }
  }
  return null;
}

/**
 * 从文本里提取 ```nora-artifacts 围栏(无则 null)。
 * 跳过被 4+ 反引号包裹的示例块(见 extractTopLevelFence 注释)。
 */
export function parseArtifactsFence(content: string | null | undefined): ArtifactsData | null {
  if (!content) return null;
  const body = extractTopLevelFence(content, "nora-artifacts");
  return body != null ? parseArtifactsJson(body) : null;
}

/**
 * 旧格式兼容(2026-09-18 统一画廊):```nora-gallery 是手机相册 photos_showcase
 * 的早期专属围栏(媒体列表)。统一画廊落地后,前端把它**转换成**统一结构渲染
 * ——组件只留一套,旧围栏(含历史消息)自动升级为原生画廊体验。
 *
 * 映射:items[].url→thumbUrl / fullUrl→url(灯箱用原图);其余字段直传。
 * 同样跳过被 4+ 反引号包裹的示例块(见 extractTopLevelFence)。
 */
export function parseLegacyGalleryFence(content: string | null | undefined): ArtifactsData | null {
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
    return {
      title: typeof legacy.title === "string" ? legacy.title : "媒体画廊",
      summary: legacy.count != null ? `共 ${legacy.count} 项` : undefined,
      sections: [{
        kind: "media",
        items: legacy.items.map((it) => ({
          kind: it.type === "video" ? ("video" as const) : ("image" as const),
          // 统一画廊约定:url=灯箱用(原图优先), thumbUrl=网格用(缩略图)
          url: it.fullUrl ?? it.url ?? "",
          thumbUrl: it.url,
          name: it.filename,
          caption: it.caption ?? it.filename,
          meta: it.takenAt,
        })),
      }],
      note: legacy.note,
    };
  } catch {
    return null;
  }
}

/** 解析围栏 JSON;结构不完整返回 null(调用方降级为普通代码块,不丢内容)。 */
export function parseArtifactsJson(raw: string): ArtifactsData | null {
  try {
    const data = JSON.parse(raw) as ArtifactsData;
    if (!data || typeof data.title !== "string" || !Array.isArray(data.sections)) {
      return null;
    }
    // 过滤空段;全空则视为无效(避免渲染空壳)。
    // 各类型的内容载体不同:items(media/files/diff/timeline/keyvalue/list)、
    // rows(table)、text(text)——任一非空即保留。
    const sections = data.sections.filter((s) => {
      if (!s || typeof s !== "object") return false;
      if (Array.isArray(s.items) && s.items.length > 0) return true;
      if (Array.isArray(s.rows) && s.rows.length > 0) return true;
      if (typeof s.text === "string" && s.text.trim() !== "") return true;
      return false;
    });
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
