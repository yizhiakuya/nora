import { randomId } from "@/lib/utils";
import { cached, invalidateForPath } from "./requestCache";

export const API_BASE = "/api";
export const USE_BACKEND = import.meta.env.VITE_USE_BACKEND === "true";

/**
 * 浏览器侧链路 ID:每个页面会话一个(per-tab session),随全部 API 请求以
 * X-Nora-Trace-Id 传播;网关/后端透传或采纳,日志系统按它串联前后端事件。
 * 错误上报(errorReporter)也带同一 ID,与后端日志交叉检索。
 *
 * 生成走 randomId()(兼容局域网 HTTP 无 crypto.randomUUID 的环境)。
 */
const BROWSER_TRACE_ID = (() => {
  try {
    const key = "nora-browser-trace-id";
    let id = sessionStorage.getItem(key);
    if (!id) {
      id = randomId().replace(/-/g, "").slice(0, 24);
      sessionStorage.setItem(key, id);
    }
    return id;
  } catch {
    return randomId().replace(/-/g, "").slice(0, 24);
  }
})();

export function browserTraceId(): string {
  return BROWSER_TRACE_ID;
}

interface ApiEnvelope<T> {
  code: number;
  data: T;
  message?: string;
  /** 错误分类(异常处理系统):VALIDATION/DEPENDENCY/TIMEOUT/... */
  category?: string;
  /** 稳定错误码(前端精确分支用) */
  errorCode?: string;
  /** 可操作建议(直接展示) */
  hint?: string;
  /** 是否值得重试(决定「重试」按钮) */
  retryable?: boolean;
  /** 链路 ID(报障定位) */
  traceId?: string;
}

/**
 * 结构化 API 错误(异常处理系统 2026-09-12):优先携带分类/提示/重试语义,
 * 前端据 category 选文案与操作;raw 保留原始串供排障折叠展示。
 */
export class ApiError extends Error {
  readonly code: number;
  readonly category?: string;
  readonly errorCode?: string;
  readonly hint?: string;
  readonly retryable?: boolean;
  readonly traceId?: string;

  constructor(init: {
    message: string;
    code: number;
    category?: string;
    errorCode?: string;
    hint?: string;
    retryable?: boolean;
    traceId?: string;
  }) {
    super(init.message);
    this.name = "ApiError";
    this.code = init.code;
    this.category = init.category;
    this.errorCode = init.errorCode;
    this.hint = init.hint;
    this.retryable = init.retryable;
    this.traceId = init.traceId;
  }
}

function isEnvelope<T>(payload: ApiEnvelope<T> | T): payload is ApiEnvelope<T> {
  return (
    typeof payload === "object" &&
    payload !== null &&
    "code" in payload &&
    "data" in payload &&
    typeof (payload as { code?: unknown }).code === "number"
  );
}

async function parseBody<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) {
    throw new Error(`HTTP ${response.status}: response body is empty`);
  }

  let payload: ApiEnvelope<T> | T;
  try {
    payload = JSON.parse(text) as ApiEnvelope<T> | T;
  } catch (error) {
    throw new Error(`Invalid JSON response (HTTP ${response.status})`, { cause: error });
  }

  if (isEnvelope<T>(payload)) {
    if (payload.code !== 0) {
      throw new ApiError({
        message: payload.message || `API error ${payload.code}`,
        code: payload.code,
        category: payload.category,
        errorCode: payload.errorCode,
        hint: payload.hint,
        retryable: payload.retryable,
        traceId: payload.traceId,
      });
    }
    return payload.data;
  }

  return payload;
}

/**
 * 各资源的缓存 TTL（毫秒）；未列出的默认不缓存。
 *
 * 取值依据（按「数据多久变一次」定，不按「希望多快」定）：
 *   - 会话列表/历史：只有发消息才会变，写操作会失效缓存 → 给较长 TTL 无损
 *   - 模型/技能/MCP/数据源配置：改一次用很久，改的时候会写 → 60s 足够安全
 *   - 健康检查：实时性要紧，不缓存（它本身就是极轻的探活）
 *
 * 刻意不用一个全局 TTL：不同资源的「新鲜度要求」差一个数量级，
 * 统一值只能取最保守的那个，等于白白放弃收益。
 */
const CACHE_TTL_MS: Record<string, number> = {
  "/chat/sessions": 30_000,
  "/chat/settings": 30_000,
  "/models/providers": 60_000,
  "/mcp/servers": 60_000,
  "/skills": 60_000,
  "/datasources": 60_000,
  "/automations": 60_000,
  "/files": 30_000,
};

/** 该路径的缓存 TTL；0 = 不缓存（但仍参与并发去重）。 */
function ttlFor(path: string): number {
  const clean = path.split("?")[0];
  // 最长前缀优先：/chat/sessions/{id}/messages 命中 /chat/sessions 而不是别的
  let bestLen = -1;
  let bestTtl = 0;
  for (const [prefix, ttl] of Object.entries(CACHE_TTL_MS)) {
    const matches = clean === prefix || clean.startsWith(`${prefix}/`);
    if (matches && prefix.length > bestLen) {
      bestLen = prefix.length;
      bestTtl = ttl;
    }
  }
  return bestTtl;
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();

  // 只缓存 GET：非 GET 是写操作，必须落库后可见（并在成功后失效对应资源）
  if (method === "GET") {
    // 调用方显式传 signal（如「可取消」场景）时不走缓存：这类调用通常
    // 需要真实的连接生命周期，复用共享 Promise 会让取消语义变模糊
    if (init?.signal) return doRequestJson<T>(path, init);
    return cached<T>(path, ttlFor(path), false, () => doRequestJson<T>(path, init));
  }

  const result = await doRequestJson<T>(path, init);
  // 写成功后失效该资源（含子路径）的缓存：宁可多取一次，也不给用户看写前的旧值
  invalidateForPath(path);
  return result;
}

async function doRequestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-Nora-Trace-Id": BROWSER_TRACE_ID,
      ...(init?.headers ?? {}),
    },
    signal: init?.signal ?? defaultTimeoutSignal(),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    // 异常处理系统:错误响应也是信封——解析出分类/hint/retryable/traceId;
    // 解析失败(网关 HTML 错误页等)退回原始文本
    let payload: ApiEnvelope<T> | null = null;
    try {
      const parsed = JSON.parse(text) as ApiEnvelope<T>;
      if (isEnvelope<T>(parsed)) payload = parsed;
    } catch {
      // not JSON: keep raw text
    }
    const serverTrace = response.headers.get("X-Nora-Trace-Id");
    if (payload) {
      throw new ApiError({
        message: payload.message || `HTTP ${response.status}`,
        code: payload.code ?? response.status,
        category: payload.category,
        errorCode: payload.errorCode,
        hint: payload.hint,
        retryable: payload.retryable,
        traceId: payload.traceId ?? (response.status >= 500 ? serverTrace ?? undefined : undefined),
      });
    }
    // 500 类错误:响应头里有服务端 traceId,拼进错误消息——用户复制会话 ID
    // 报障时,后端日志能按这个 ID 直接定位到当次请求
    const traceSuffix = response.status >= 500 && serverTrace ? ` [trace=${serverTrace}]` : "";
    throw new Error((text || `HTTP ${response.status}`) + traceSuffix);
  }

  return parseBody<T>(response);
}

/**
 * 默认请求超时(30s):网关挂起时让 UI 尽快失败而不是永久等待。
 * 调用方显式传 signal 时以其为准;超时错误文案便于 humanizeError 识别。
 */
export function defaultTimeoutSignal(ms = 30_000): AbortSignal {
  const controller = new AbortController();
  setTimeout(() => controller.abort(new DOMException("请求超时(30s)", "TimeoutError")), ms);
  return controller.signal;
}
