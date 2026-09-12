export const API_BASE = "/api";
export const USE_BACKEND = import.meta.env.VITE_USE_BACKEND === "true";

/**
 * 浏览器侧链路 ID:每个页面会话一个(per-tab session),随全部 API 请求以
 * X-Nora-Trace-Id 传播;网关/后端透传或采纳,日志系统按它串联前后端事件。
 * 错误上报(errorReporter)也带同一 ID,与后端日志交叉检索。
 */
const BROWSER_TRACE_ID = (() => {
  try {
    const key = "nora-browser-trace-id";
    let id = sessionStorage.getItem(key);
    if (!id) {
      id = crypto.randomUUID().replace(/-/g, "").slice(0, 24);
      sessionStorage.setItem(key, id);
    }
    return id;
  } catch {
    return crypto.randomUUID().replace(/-/g, "").slice(0, 24);
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

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
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
