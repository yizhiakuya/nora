/**
 * 时间格式化统一工具(2026-09-21 收敛)。
 *
 * 此前 toHm / formatTime / getTime / getLogTime 等 6+ 处各写一遍
 * toLocaleTimeString,且多处都带着同一段注释「后端 ISO 串是本地挂钟、
 * 不能直接 new Date()」——同类知识复制多份,改一处容易漏其它。
 * 本模块收口这些语义:
 *
 * - **本地挂钟串**(后端 created_at 等,无时区后缀):直接正则截取,
 *   不经 new Date 解析(部分浏览器会把无时区串按 UTC 解析、显示偏差);
 * - **带时区时间戳**(媒体 EXIF 等,+08:00 完整 ISO / 毫秒数):正常走
 *   Date,再用本地时区字段格式化;
 * - **当前时间**:统一 zh-CN 24 小时制,一处定义。
 */

/** 两位补零。 */
const pad = (n: number) => String(n).padStart(2, "0");

/** 当前时间 HH:mm(消息时间戳/通知等展示用)。 */
export function nowHm(): string {
  const d = new Date();
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 当前时间 HH:mm:ss(日志行时间戳用)。 */
export function nowHms(): string {
  const d = new Date();
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 当前本地时间 "YYYY-MM-DD HH:mm"(mock 模式的时间戳字段用;不走 toISOString——那是 UTC)。 */
export function nowDateTime(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 当前本地日期 "YYYY-MM-DD"(导出文件名等;不走 toISOString——那是 UTC,凌晨会差一天)。 */
export function nowDate(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * 后端本地挂钟 ISO 串 → "HH:mm"(如 2026-09-09T11:38:12 → "11:38")。
 * 正则直接截取,保本地语义;非本地挂钟形态(带时区的完整 ISO)回落 Date 解析。
 */
export function hmFromLocalIso(raw?: string | null): string {
  if (!raw) return "";
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(raw);
  if (m) return `${m[4]}:${m[5]}`;
  const d = new Date(raw);
  return isNaN(d.getTime()) ? "" : `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * 后端本地挂钟 ISO 串 → "MM-DD HH:mm"(任务下次执行时间/执行记录等)。
 * 截取法同 {@link hmFromLocalIso};解析失败返回 "—"。
 */
export function mdHmFromLocalIso(ts?: string | null): string {
  if (!ts) return "—";
  const m = /^\d{4}-(\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(ts);
  return m ? `${m[1]} ${m[2]}` : "—";
}

/** Date / 毫秒时间戳 → "MM-DD HH:mm"(媒体缓存/拍摄时间等带时区数据用)。 */
export function mdHm(date: Date | number): string {
  const d = date instanceof Date ? date : new Date(date);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
