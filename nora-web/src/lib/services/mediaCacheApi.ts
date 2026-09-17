import { requestJson, USE_BACKEND } from "@/lib/api/client";

/** 媒体缓存条目(后端 MediaCacheService.CachedItem)。 */
export interface CachedMediaItem {
  /** 缓存键(sha256 前段;删除用) */
  key: string;
  /** 原始媒体 URL(相册内容地址) */
  url: string;
  contentType: string;
  size: number;
  /** 出图档位:high=原尺寸 / low=省流量(蜂窝下看过) */
  quality: string | null;
  phoneKind: string | null;
  /** 最近访问时间(mtime;LRU 序) */
  savedAt: number;
}

export interface CachedMediaList {
  items: CachedMediaItem[];
  count: number;
  totalBytes: number;
  /** 缓存磁盘目录(展示"真实磁盘位置"用) */
  dir: string;
}

/**
 * 媒体缓存接入层(文件中心「媒体缓存」文件夹):
 * - list   → GET    /api/media/cached
 * - remove → DELETE /api/media/cached/{key}
 * - clear  → DELETE /api/media/cached
 *
 * 缓存内容 = 相册等远程媒体的本地副本(缩略图/播放流/原片),
 * 让媒体跨会话秒开、手机离线也能看。
 */
export const mediaCacheApi = {
  async list(): Promise<CachedMediaList | null> {
    if (!USE_BACKEND) return null;
    return requestJson<CachedMediaList>("/media/cached");
  },
  async remove(key: string): Promise<void> {
    if (!USE_BACKEND) return;
    await requestJson<boolean>(`/media/cached/${encodeURIComponent(key)}`, { method: "DELETE" });
  },
  async clear(): Promise<number> {
    if (!USE_BACKEND) return 0;
    return requestJson<number>("/media/cached", { method: "DELETE" });
  },
  /** 缓存条目的预览 URL(经 /api/media/cache 代理,命中即磁盘直出)。 */
  previewUrl(url: string): string {
    return `/api/media/cache?url=${encodeURIComponent(url)}`;
  },
};

/** 从相册 URL 提取显示名(如 /photo/8678/thumb → "8678 · 缩略图")。 */
export function cachedItemLabel(item: CachedMediaItem): string {
  const m = item.url.match(/\/photo\/(\d+)\/(content|thumb|video|sheet)/);
  if (!m) return item.key.slice(0, 12);
  const kindText: Record<string, string> = {
    content: "原片/原图",
    thumb: "缩略图",
    video: "播放流",
    sheet: "拼图",
  };
  return `${m[1]} · ${kindText[m[2]] ?? m[2]}`;
}

/** 条目是否视频(播放流/原片 mime 判断)。 */
export function isVideoItem(item: CachedMediaItem): boolean {
  return item.contentType.startsWith("video/");
}
