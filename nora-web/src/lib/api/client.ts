export const API_BASE = "/api";
export const USE_BACKEND = import.meta.env.VITE_USE_BACKEND === "true";

interface ApiEnvelope<T> {
  code: number;
  data: T;
  message?: string;
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
      throw new Error(payload.message || `API error ${payload.code}`);
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
      ...(init?.headers ?? {}),
    },
    signal: init?.signal ?? defaultTimeoutSignal(),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `HTTP ${response.status}`);
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
