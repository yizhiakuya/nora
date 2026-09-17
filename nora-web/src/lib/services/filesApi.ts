import { FileItem, FilePreview, FilePreviewKind } from "@/types";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { FileText, FileSpreadsheet, FileImage, File } from "lucide-react";

/** 后端 file-service 响应的 FileItem(camelCase) */
interface BackendFileItem {
  id: number;
  name: string;
  mimeType: string;
  sizeBytes: number | null;
  indexed: boolean;
  createdAt: string;
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

function toPreview(p: BackendFilePreview, id: number, name: string): FilePreview {
  if (p.textContent) {
    return { kind: "text", text: p.textContent };
  }
  // 后端 Tika 提取不到文本(图片/视频/空文件)时按扩展名给预览 kind
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext)) {
    // 图片:文本提取为空,给 raw 端点 URL 让 <img> 直接渲染原始字节
    return { kind: "image", imageUrl: `/api/files/${id}/raw` };
  }
  if (["mp4", "webm", "mov", "m4v", "ogv", "mkv"].includes(ext)) {
    // 视频:raw 端点直出字节,<video> 流式播放(依赖服务端 Range 支持)
    return { kind: "video", mediaUrl: `/api/files/${id}/raw` };
  }
  if (["mp3", "wav", "ogg", "m4a", "flac", "aac"].includes(ext)) {
    return { kind: "audio", mediaUrl: `/api/files/${id}/raw` };
  }
  return { kind: "unknown" as FilePreviewKind };
}

async function requestRaw<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, signal: init?.signal ?? defaultTimeoutSignal() });
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
    const items = await requestJson<BackendFileItem[]>("/files");
    return items.map(toFileItem);
  },

  async uploadFile(file: globalThis.File): Promise<FileItem> {
    const form = new FormData();
    form.append("file", file);
    const item = await requestRaw<BackendFileItem>("/files/upload", { method: "POST", body: form });
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
};

export { toFileItem, humanSize, mimeToType, getFileMeta };
export type { BackendFileItem, BackendFilePreview };
