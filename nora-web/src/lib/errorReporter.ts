/**
 * 前端全局错误上报:window.onerror / unhandledrejection / 手动 log 都进这里。
 * - 环形缓冲去重(同一错误 10s 窗口内只报一次,防错误风暴刷爆后端);
 * - 载荷带 browserTraceId,与全部 API 请求的 X-Nora-Trace-Id 同值,
 *   后端 JSON 日志按 traceId 串联前后端事件;
 * - 上报走 fire-and-forget,自身失败静默(排障通道不能成为故障源)。
 */
import { browserTraceId } from "@/lib/api/client";
import { USE_BACKEND } from "@/lib/api/client";

const DEDUP_WINDOW_MS = 10_000;
const recent = new Map<string, number>();

function shouldSend(key: string): boolean {
  const now = Date.now();
  const last = recent.get(key) ?? 0;
  recent.set(key, now);
  if (recent.size > 100) {
    // 防慢性泄漏:清理过期项
    recent.forEach((t, k) => {
      if (now - t > DEDUP_WINDOW_MS) recent.delete(k);
    });
  }
  return now - last > DEDUP_WINDOW_MS;
}

export interface FrontendLogPayload {
  level?: "info" | "warn" | "error";
  event: string;
  message?: string;
  stack?: string;
  extra?: Record<string, unknown>;
}

export function reportToBackend(payload: FrontendLogPayload): void {
  if (!USE_BACKEND) return;
  const key = `${payload.event}:${payload.message?.slice(0, 120) ?? ""}`;
  if (!shouldSend(key)) return;
  try {
    void fetch("/api/log/frontend", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Nora-Trace-Id": browserTraceId() },
      body: JSON.stringify({
        level: payload.level ?? "error",
        event: payload.event,
        message: payload.message ?? "",
        stack: payload.stack ?? "",
        url: location.href,
        traceId: browserTraceId(),
        sessionId: (() => {
          try {
            return JSON.parse(localStorage.getItem("chat-sessions") ?? "{}")?.state?.activeId ?? "";
          } catch {
            return "";
          }
        })(),
        extra: payload.extra ?? null,
      }),
      keepalive: true,
    }).catch(() => undefined);
  } catch {
    /* 上报通道绝不抛错 */
  }
}

/** 应用启动时调用一次:接管全局错误兜底。 */
export function installGlobalErrorReporting(): void {
  window.addEventListener("error", (e) => {
    reportToBackend({
      event: "window.onerror",
      message: e.message,
      stack: e.error?.stack ?? `${e.filename}:${e.lineno}:${e.colno}`,
      extra: { source: e.filename, line: e.lineno },
    });
  });
  window.addEventListener("unhandledrejection", (e) => {
    const reason = e.reason instanceof Error ? e.reason : new Error(String(e.reason));
    reportToBackend({
      event: "unhandledrejection",
      message: reason.message,
      stack: reason.stack,
    });
  });
  // 白屏类灾难(React 渲染树整体崩溃)无法自捕获,靠 ErrorBoundary;这里补
  // 资源加载失败(脚本/样式 404 → 白屏常见根因)
  window.addEventListener("error", (e) => {
    const t = e.target as HTMLElement | null;
    if (t && (t.tagName === "SCRIPT" || t.tagName === "LINK")) {
      reportToBackend({
        event: "resource.load-failed",
        message: (t as HTMLScriptElement).src ?? "",
        level: "warn",
      });
    }
  }, true);
}
