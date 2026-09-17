/**
 * 媒体 URL 的缓存代理(2026-09-17):远程媒体(相册等经隧道拉取)统一
 * 换成后端缓存端点 /api/media/cache?url=...,后端磁盘缓存一次拉取长期复用。
 *
 * 收益:
 * - 同一媒体跨会话/刷新秒开(手机相册直连单次 ~3.2s,手机离线时不可用);
 * - 浏览器侧 7 天强缓存 + Range(视频可 seek);
 * - 手机离线后已缓存内容仍可查看。
 *
 * 仅对 http(s) 远程地址生效;本地/相对路径(如 /api/files/... )原样返回。
 */

/** 后端缓存端点(经网关;相对路径使浏览器直接走当前 host)。 */
export function mediaCacheUrl(url: string): string {
  if (!/^https?:\/\//i.test(url)) return url;
  return `/api/media/cache?url=${encodeURIComponent(url)}`;
}

/**
 * 相册缩略图变体:/content → /thumb(网格快);已是 /thumb 或非相册地址原样。
 * 与后端无耦合——纯 URL 形态判断。
 */
export function thumbVariant(url: string): string {
  return url.replace(/\/content(\?|$)/, "/thumb$1");
}
