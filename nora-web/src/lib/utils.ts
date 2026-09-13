import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * 生成随机 ID（UUID v4 形态）。
 *
 * `crypto.randomUUID` 只在**安全上下文**可用（HTTPS 或 localhost）。
 * 局域网 HTTP 访问（如手机用 http://192.168.x.x:3001 打开工作台）时它
 * 不存在——此前直接调用会让页面在初始化阶段白屏（实测踩过）。
 * 这里降级：优先 randomUUID → getRandomValues 手拼 v4 → Math.random 兜底。
 */
export function randomId(): string {
  const c = typeof globalThis !== "undefined" ? (globalThis.crypto as Crypto | undefined) : undefined
  if (c?.randomUUID) {
    try {
      return c.randomUUID()
    } catch {
      // 落到下面的降级路径
    }
  }
  const bytes = new Uint8Array(16)
  if (c?.getRandomValues) {
    c.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
