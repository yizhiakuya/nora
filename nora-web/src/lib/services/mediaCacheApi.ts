import { requestJson } from "@/lib/api/client";import { withAuthToken } from "@/lib/auth";

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
    return requestJson<CachedMediaList>("/media/cached");
  },
  async remove(key: string): Promise<void> {
    await requestJson<boolean>(`/media/cached/${encodeURIComponent(key)}`, { method: "DELETE" });
  },
  async clear(): Promise<number> {
    return requestJson<number>("/media/cached", { method: "DELETE" });
  },
  /**
   * 把缓存条目保存到文件中心(转为正式知识资产:可索引/可引用/可被 AI 读取)。
   * 服务端直传,浏览器不参与大文件搬运。
   */
  async saveToFiles(key: string, filename?: string): Promise<{ name: string; size: number; detail: string }> {
    return requestJson<{ name: string; size: number; detail: string }>(
      `/media/cached/${encodeURIComponent(key)}/save`,
      { method: "POST", body: JSON.stringify(filename ? { filename } : {}) }
    );
  },
  /** 缓存条目的预览 URL(经 /api/media/cache 代理,命中即磁盘直出)。
   *  img/video 标签无法带 header:令牌走 ?token=(2026-09-20 修复:此前裸 URL,
   *  开启网关令牌后会被 401 拦截)。 */
  previewUrl(url: string): string {
    return withAuthToken(`/api/media/cache?url=${encodeURIComponent(url)}`);
  },
};

/** 从相册 URL 提取显示名(如 /photo/8678/thumb → "8678 · 缩略图")。 */
export function cachedItemLabel(item: CachedMediaItem): string {
  const m = item.url.match(/\/photo\/(\d+)\/(content|thumb|video|sheet)/);
  if (!m) return item.key.slice(0, 12);
  const isVideo = item.contentType.startsWith("video/");
  const kindText: Record<string, string> = {
    content: isVideo ? "原片" : "原图",
    thumb: "缩略图",
    video: "播放流",
    sheet: "拼图",
  };
  return `${m[1]} · ${kindText[m[2]] ?? m[2]}`;
}

/**
 * 网格展示用的缩略图 URL。
 *
 * 视频缓存条目(播放流/原片)没有内嵌封面——改写为手机同 id 的 `/thumb`
 * 端点(它对视频返回封面帧),经 /api/media/cache 拉取(小图,顺手入缓存)。
 * 已是缩略图的条目直接用;拼图等无法改写的返回原 URL(本身就是小图)。
 */
export function thumbUrlFor(item: CachedMediaItem): string {
  if (/\/photo\/\d+\/thumb/.test(item.url)) return item.url;
  const derived = item.url.replace(/\/(video|content)(\?|$)/, "/thumb$2");
  return derived;
}

/** 条目是否视频(播放流/原片 mime 判断)。 */
export function isVideoItem(item: CachedMediaItem): boolean {
  return item.contentType.startsWith("video/");
}
