import { withAuthToken } from "@/lib/auth";

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

/** 后端缓存端点(经网关;相对路径使浏览器直接走当前 host)。
 *  媒体 src(img/video 标签)无法带 header:令牌走 ?token=。 */
export function mediaCacheUrl(url: string): string {
  if (!/^https?:\/\//i.test(url)) return url;
  return withAuthToken(`/api/media/cache?url=${encodeURIComponent(url)}`);
}

/**
 * 相册缩略图变体:/content → /thumb(网格快);已是 /thumb 或非相册地址原样。
 * 与后端无耦合——纯 URL 形态判断。
 */
export function thumbVariant(url: string): string {
  return url.replace(/\/content(\?|$)/, "/thumb$1");
}

/**
 * 视频压缩流变体(2026-09-17):相册视频原片 ~17Mbps 经隧道播放费流量又卡,
 * 手机端 /photo/{id}/video 端点会把视频转码到网络档位码率(H.264,~1/5-1/13 体积,
 * 浏览器直放,结果按档位缓存)。
 *
 * 把原片 /content URL 改写为 /video——历史消息里持久化的画廊 JSON 是旧 URL,
 * 渲染层统一改写,不必等消息重建。非相册地址(无 /photo/{id}/content 形态)原样返回。
 */
export function videoStreamVariant(url: string): string {
  return url.replace(/(\/photo\/\d+\/)content(\?|$)/, "$1video$2");
}

/**
 * 原图变体(2026-09-18):/thumb → /content 反向升级——灯箱/全屏查看用。
 *
 * 为什么需要:工具结果的 markdown 图片链接常是缩略图 URL(/thumb,512px),
 * 灯箱直接加载会在全屏放大下模糊(实测踩过:用户反馈「分辨率太小」)。
 * 灯箱的大图应始终取原图档,缩略图只做网格/占位。
 * 非相册地址(无 /thumb 形态)原样返回。
 */
export function originalVariant(url: string): string {
  return url.replace(/\/thumb(\?|$)/, "/content$1");
}
