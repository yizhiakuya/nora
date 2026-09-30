import type { FilePreview, ViewerFile, ViewerFileError } from "@/types";
import { requestJson } from "@/lib/api/client";
import { withAuthToken } from "@/lib/auth";
import { filesApi } from "./filesApi";
import { workspaceApi } from "./workspaceApi";
import { mediaCacheUrl, originalVariant } from "@/lib/mediaCache";

export const viewerApi = {
  async resolve(targets: string[]) {
    if (targets.length === 1) {
      const url = new URL(targets[0], window.location.origin);
      const mediaUrl = url.origin === window.location.origin && url.pathname === "/api/media/cache" ? url.searchParams.get("url") ?? "" : targets[0];
      if (/^https?:\/\//i.test(mediaUrl) && !viewerTargetFromUrl(mediaUrl)) {
        const file = await requestJson<ViewerFile>("/viewer/media", { method: "POST", body: JSON.stringify({ url: mediaUrl }) });
        const originalUrl = originalVariant(mediaUrl);
        if (file.previewKind === "image" || file.previewKind === "video") {
          void requestJson("/media/warm", { method: "POST", body: JSON.stringify({ url: originalUrl }) }).catch(() => { /* 原片预热失败不影响当前预览。 */ });
        }
        return { files: [{ ...file, originalUrl }], errors: [] as ViewerFileError[] };
      }
    }
    return requestJson<{ files: ViewerFile[]; errors: ViewerFileError[] }>("/viewer/resolve", {
      method: "POST", body: JSON.stringify({ targets: targets.map(target => viewerTargetFromUrl(target) ?? target) }),
    });
  },
  async preview(file: ViewerFile): Promise<FilePreview> {
    const url = viewerApi.rawUrl(file);
    switch (file.previewKind) {
      case "image": return { kind: "image", imageUrl: url };
      case "video": case "audio": case "pdf": return { kind: file.previewKind, mediaUrl: url };
      case "office": return filesApi.fetchPreview(Number(file.target.slice(5)), file.name);
      case "unknown": return { kind: "unknown" };
    }
    const text = await requestJson<{ content: string; hash: string | null; truncated: boolean }>(
      `/viewer/text?target=${encodeURIComponent(file.target)}`, { cache: "no-store" }
    );
    const preview: FilePreview = { kind: file.previewKind, text: text.content, hash: text.hash, truncated: text.truncated };
    if (file.previewKind === "csv") {
      const Papa = (await import("papaparse")).default;
      const parsed = Papa.parse<string[]>(text.content, {
        delimiter: file.name.toLowerCase().endsWith(".tsv") ? "\t" : ",",
        skipEmptyLines: "greedy", preview: 202,
      });
      const seriousError = parsed.errors.find(error => error.code !== "TooFewFields" && error.code !== "TooManyFields");
      if (seriousError && !text.truncated) throw new Error(`表格无法解析：${seriousError.message}`);
      preview.table = { columns: (parsed.data[0] ?? []).slice(0, 100), rows: parsed.data.slice(1, 201).map(row => row.slice(0, 100)) };
      preview.tableTruncated = parsed.data.length > 201 || parsed.meta.truncated || text.truncated || parsed.data.some(row => row.length > 100);
    }
    return preview;
  },
  rawUrl(file: Pick<ViewerFile, "target" | "version" | "originalUrl">, download = false): string {
    if (download && file.originalUrl) return mediaCacheUrl(file.originalUrl);
    if (file.target.startsWith("workspace:")) {
      return withAuthToken(`/api/workspace/file/raw?path=${encodeURIComponent(file.target.slice(10))}&download=${download}&v=${encodeURIComponent(file.version)}`);
    }
    if (file.target.startsWith("file:")) {
      const id = Number(file.target.slice(5));
      return download ? filesApi.downloadUrl([id]) : withAuthToken(`/api/files/${id}/raw?v=${encodeURIComponent(file.version)}`);
    }
    return withAuthToken(`/api/media/cached/${encodeURIComponent(file.target.slice(6))}/raw?v=${encodeURIComponent(file.version)}`);
  },
  save(file: ViewerFile, content: string, expectedHash: string | null) {
    if (!file.capabilities.edit || !file.target.startsWith("workspace:")) throw new Error("此文件不能编辑");
    return workspaceApi.writeFile(file.target.slice(10), content, expectedHash);
  },
};

/** 历史画廊里的本地 API 地址转换成稳定引用，不传递 URL 上的令牌。 */
export function viewerTargetFromUrl(value: string): string | null {
  if (/^(workspace:|file:|media:)/.test(value)) return value;
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin) return null;
    if (url.pathname === "/api/workspace/file/raw" && url.searchParams.has("path")) return `workspace:${url.searchParams.get("path")}`;
    const file = /^\/api\/files\/(\d+)\/raw$/.exec(url.pathname);
    if (file) return `file:${file[1]}`;
    const media = /^\/api\/media\/cached\/([a-f0-9]{8,64})\/raw$/.exec(url.pathname);
    if (media) return `media:${media[1]}`;
  } catch { /* 未识别的历史地址由调用方处理 */ }
  return null;
}
