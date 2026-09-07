/**
 * 相对时间格式化(会话列表/消息元信息用):
 * 刚刚 / N分钟前 / N小时前 / 昨天 / M月D日 / YYYY年M月D日。
 * 纯函数便于测试;不接受 locale 参数 —— 当前应用固定中文。
 */
export function relativeTime(ts: number, now: number = Date.now()): string {
  if (!Number.isFinite(ts)) return "";
  const diff = now - ts;
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;

  if (diff < minute) return "刚刚";
  if (diff < hour) return `${Math.floor(diff / minute)}分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)}小时前`;

  const d = new Date(ts);
  const n = new Date(now);
  const startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  if (ts >= startOfToday - day && ts < startOfToday) return "昨天";
  if (d.getFullYear() === n.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`;
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
}
