import { FileItem, FilePreview, FilePreviewKind } from "@/types";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { authHeaders, withAuthToken } from "@/lib/auth";
import { FileText, FileSpreadsheet, FileImage, File } from "lucide-react";

/** 后端 file-service 响应的 FileItem(camelCase) */
interface BackendFileItem {
  id: number;
  name: string;
  mimeType: string;
  sizeBytes: number | null;
  indexed: boolean;
  createdAt: string;
  /** 所属文件夹;null = 根目录 */
  folderId?: number | null;
}

/** 后端文件夹行。 */
export interface BackendFolder {
  id: number;
  name: string;
  fileCount: number;
  /** 文件夹内文件总字节数 */
  totalBytes: number;
  createdAt: string | null;
}

function humanSize(bytes: number | null): string {
  if (bytes == null || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function mimeToType(mime: string, name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (mime.includes("pdf")) return "PDF 文档";
  if (["xlsx", "xls", "csv"].includes(ext) || mime.includes("spreadsheet")) return "Excel 表格";
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext) || mime.startsWith("image/")) return imageLabel(ext);
  if (["docx", "doc"].includes(ext) || mime.includes("word")) return "Word 文档";
  if (["txt", "md"].includes(ext) || mime.startsWith("text/")) return "纯文本";
  return "文档";
}

/** 图片类型标签:按真实扩展名显示(jpg 曾一律标成「PNG 图像」)。 */
function imageLabel(ext: string): string {
  if (ext === "jpg" || ext === "jpeg") return "JPG 图像";
  if (ext === "png") return "PNG 图像";
  if (ext === "gif") return "GIF 图像";
  if (ext === "webp") return "WebP 图像";
  return "图像";
}

/** 后端行 → 前端 FileItem(icon/color 由展示层决定,这里给默认) */
function toFileItem(f: BackendFileItem): FileItem {
  const type = mimeToType(f.mimeType ?? "", f.name);
  const meta = getFileMeta(f.name, type);
  return {
    id: f.id,
    name: f.name,
    type,
    size: humanSize(f.sizeBytes),
    date: f.createdAt?.slice(0, 16).replace("T", " ") ?? "",
    icon: meta.icon,
    color: meta.color,
    indexed: f.indexed,
    folderId: f.folderId ?? null,
  };
}

function getFileMeta(name: string, type?: string) {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["xlsx", "csv"].includes(ext)) {
    return { type: type || "Excel 表格", icon: FileSpreadsheet, color: "text-green-600 dark:text-green-400" };
  }
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext)) {
    return { type: type || imageLabel(ext), icon: FileImage, color: "text-purple-500" };
  }
  if (["docx", "doc"].includes(ext)) {
    return { type: type || "Word 文档", icon: FileText, color: "text-blue-500" };
  }
  if (["txt", "md"].includes(ext)) {
    return { type: type || "纯文本", icon: File, color: "text-gray-500" };
  }
  return { type: type || "PDF 文档", icon: FileText, color: "text-red-500" };
}

/** 后端 file-service 响应的 FilePreview */
interface BackendFilePreview {
  fileId: number;
  type: string;
  textContent: string | null;
  name: string;
  size: string;
}

/**
 * 后端预览 → 前端 FilePreview。
 *
 * 2026-09-17 重排判定顺序:**先按扩展名决定渲染形态,再决定数据来源**。
 * 此前「有文本就走 text」导致 PDF 走了纯文本渲染(用户要看版式,不是
 * 提取出的乱行);现在 PDF 用浏览器内置查看器(raw 字节),Excel/Word
 * 用提取文本做结构化渲染,图片/音视频用 raw 流。
 */
function toPreview(p: BackendFilePreview, id: number, name: string): FilePreview {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  // 图片/视频 src 无法带 header:令牌走 ?token=
  const rawUrl = withAuthToken(`/api/files/${id}/raw`);

  // PDF:浏览器内置查看器(iframe 直连 raw;保留版式/翻页/缩放/打印)
  if (ext === "pdf") {
    return { kind: "pdf", mediaUrl: rawUrl, pages: estimatePdfPages(p.textContent) };
  }
  // 图片 / 音视频:raw 字节流
  if (["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"].includes(ext)) {
    return { kind: "image", imageUrl: rawUrl };
  }
  if (["mp4", "webm", "mov", "m4v", "ogv", "mkv"].includes(ext)) {
    return { kind: "video", mediaUrl: rawUrl };
  }
  if (["mp3", "wav", "ogg", "m4a", "flac", "aac"].includes(ext)) {
    return { kind: "audio", mediaUrl: rawUrl };
  }
  // 表格:提取文本解析成二维表(csv 逗号 / tsv 制表符 / Excel 制表符或对齐空格)
  if (["xlsx", "xls", "csv", "tsv"].includes(ext)) {
    const table = parseDelimitedTable(p.textContent ?? "", ext);
    if (table.rows.length > 0) {
      return { kind: "excel", table };
    }
    // 解析不出结构(空表/异常格式):降级纯文本
    return p.textContent ? { kind: "text", text: p.textContent } : { kind: "unknown" as FilePreviewKind };
  }
  // Word:提取文本(标题/段落)
  if (["docx", "doc"].includes(ext)) {
    return { kind: "word", text: p.textContent ?? "" };
  }
  // 其余:有文本按文本预览,无文本不支持
  if (p.textContent) {
    return { kind: "text", text: p.textContent };
  }
  return { kind: "unknown" as FilePreviewKind };
}

/** 从 PDF 提取文本粗略估计页数(Tika 的分页符或字数估算;仅展示用)。 */
function estimatePdfPages(text: string | null): number {
  if (!text) return 1;
  const formFeeds = (text.match(/\f/g) ?? []).length;
  if (formFeeds > 0) return formFeeds + 1;
  // 每页约 1800 字(中英混排粗估)
  return Math.max(1, Math.round(text.length / 1800));
}

/**
 * 解析分隔符文本为二维表。
 *
 * 按文件类型选分隔符:csv 用逗号(支持双引号包裹字段),tsv/Excel 优先
 * 制表符(Tika 对 xlsx 输出制表符分隔行),退化为连续 2+ 空格对齐分列。
 * 首行作表头;单列(无分隔结构)不当作表格。
 */
function parseDelimitedTable(text: string, ext: string): { columns: string[]; rows: string[][] } {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length === 0) return { columns: [], rows: [] };
  const splitLine = (line: string): string[] => {
    if (ext === "csv") {
      // CSV:按逗号分列,处理双引号包裹(含逗号的字段)
      const out: string[] = [];
      let cur = "";
      let inQuote = false;
      for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (inQuote) {
          if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
          else if (ch === '"') inQuote = false;
          else cur += ch;
        } else if (ch === '"') {
          inQuote = true;
        } else if (ch === ",") {
          out.push(cur.trim());
          cur = "";
        } else {
          cur += ch;
        }
      }
      out.push(cur.trim());
      return out;
    }
    if (line.includes("\t")) return line.split("\t").map((c) => c.trim());
    return line.split(/\s{2,}/).map((c) => c.trim());
  };
  const columns = splitLine(lines[0]);
  // 单列(没有分隔结构):不当作表格
  if (columns.length < 2) return { columns: [], rows: [] };
  const rows = lines.slice(1).map(splitLine).slice(0, 500); // 上限 500 行,超出提示下载
  return { columns, rows };
}

async function requestRaw<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, headers: { ...authHeaders(), ...(init?.headers ?? {}) }, signal: init?.signal ?? defaultTimeoutSignal() });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `HTTP ${response.status}`);
  }
  const payload = (await response.json()) as { code: number; data: T; message?: string };
  if (payload.code !== 0) {
    throw new Error(payload.message || `API error ${payload.code}`);
  }
  return payload.data;
}

// 请求默认超时策略(30s):与 client.ts 共用,避免挂起时 UI 永久等待
import { defaultTimeoutSignal } from "@/lib/api/client";

/**
 * 文件中心后端接入层(USE_BACKEND 开关):
 * - listFiles    → GET  /api/files          → FileItem[]
 * - uploadFile   → POST /api/files/upload   (multipart) → FileItem
 * - deleteFiles  → DELETE /api/files?ids=   → void
 * - indexFile    → POST /api/files/{id}/index → FileItem(异步索引,返回时 indexed 可能仍为 false)
 * - fetchPreview → GET  /api/files/{id}/preview → FilePreview
 */
export const filesApi = {
  async listFiles(): Promise<FileItem[]> {
    if (!USE_BACKEND) return [];
    // 拉全量(含 folderId),文件夹过滤在前端做——store 是唯一数据源,
    // 避免每个文件夹切换都发一次请求
    const items = await requestJson<BackendFileItem[]>("/files");
    return items.map(toFileItem);
  },

  async uploadFile(file: globalThis.File, folderId?: number | null): Promise<FileItem> {
    const form = new FormData();
    form.append("file", file);
    if (folderId != null) form.append("folderId", String(folderId));
    // 上传超时放宽(2026-09-21):默认 30s 对 100MB 上限的文件(家宽上行
    // 40-80s)会被中途掐断——上传不是"挂起请求",给 10 分钟传输窗口
    const item = await requestRaw<BackendFileItem>("/files/upload", {
      method: "POST",
      body: form,
      signal: defaultTimeoutSignal(10 * 60_000),
    });
    return toFileItem(item);
  },

  async deleteFiles(ids: number[]): Promise<void> {
    await requestJson<void>(`/files?ids=${ids.join(",")}`, { method: "DELETE" });
  },

  async indexFile(id: number): Promise<FileItem> {
    const item = await requestJson<BackendFileItem>(`/files/${id}/index`, { method: "POST" });
    return toFileItem(item);
  },

  async fetchPreview(id: number, name: string): Promise<FilePreview> {
    const p = await requestJson<BackendFilePreview>(`/files/${id}/preview`);
    return toPreview(p, id, name);
  },

  /** 重命名文件。 */
  async renameFile(id: number, name: string): Promise<FileItem> {
    const item = await requestJson<BackendFileItem>(`/files/${id}/name`, {
      method: "PUT",
      body: JSON.stringify({ name }),
    });
    return toFileItem(item);
  },

  /** 批量移动文件到文件夹(null = 根目录)。 */
  async moveFiles(ids: number[], folderId: number | null): Promise<number> {
    return requestJson<number>("/files/move", {
      method: "PUT",
      body: JSON.stringify({ ids, folderId }),
    });
  },

  // ---------- 文件夹 ----------

  async listFolders(): Promise<BackendFolder[]> {
    if (!USE_BACKEND) return [];
    return requestJson<BackendFolder[]>("/files/folders");
  },

  async createFolder(name: string): Promise<BackendFolder> {
    return requestJson<BackendFolder>("/files/folders", {
      method: "POST",
      body: JSON.stringify({ name }),
    });
  },

  async renameFolder(id: number, name: string): Promise<BackendFolder> {
    return requestJson<BackendFolder>(`/files/folders/${id}`, {
      method: "PUT",
      body: JSON.stringify({ name }),
    });
  },

  /** 删除文件夹(文件回到根目录);返回移回的文件数。 */
  async deleteFolder(id: number): Promise<number> {
    return requestJson<number>(`/files/folders/${id}`, { method: "DELETE" });
  },

  /** 批量下载 URL(浏览器直接打开触发下载;单文件出原文件,多文件打 zip)。 */
  downloadUrl(ids: number[]): string {
    // 浏览器直接导航下载(window.open)无法带 header:令牌走 ?token=
    return withAuthToken(`/api/files/download?ids=${ids.join(",")}`);
  },

  // ---------- 回收站 ----------

  async listTrash(): Promise<TrashedFile[]> {
    if (!USE_BACKEND) return [];
    return requestJson<TrashedFile[]>("/files/trash");
  },

  /** 从回收站恢复(回到根目录)。 */
  async restoreTrash(ids: number[]): Promise<number> {
    return requestJson<number>("/files/trash/restore", {
      method: "POST",
      body: JSON.stringify({ ids }),
    });
  },

  /** 永久删除(不可恢复;磁盘文件一并删除)。 */
  async purgeTrash(ids: number[]): Promise<number> {
    return requestJson<number>(`/files/trash?ids=${ids.join(",")}`, { method: "DELETE" });
  },
};

/** 回收站条目(后端 TrashedItem)。 */
export interface TrashedFile {
  item: BackendFileItem;
  deletedAt: string | null;
}

export { toFileItem, humanSize, mimeToType, getFileMeta };
export type { BackendFileItem, BackendFilePreview };
