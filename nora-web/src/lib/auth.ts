/**
 * 令牌登录客户端(2026-09-19)。
 *
 * 单用户自部署工作台的访问门:服务端配了 NORA_AUTH_TOKEN 时,所有 API
 * 需携带令牌;未配置则免登录(本地开发零配置)。
 *
 * - 令牌存 localStorage(单用户本机场景,与"记住我"等价);
 * - 请求头统一走 {@link authHeaders}(Authorization: Bearer);
 * - SSE/EventSource 无法自定义 header——URL 走 {@link withAuthToken}
 *   追加 ?token=(服务端 filter 支持两种通道);
 * - 401 统一处理:{@link handleUnauthorized} 清令牌并跳登录页(带回跳地址)。
 */
const TOKEN_KEY = "nora-auth-token";

/** 读取当前令牌(未登录返回空串)。 */
export function getAuthToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? "";
  } catch {
    return "";
  }
}

/** 保存令牌(登录成功后调用)。 */
export function setAuthToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* 隐私模式:令牌只活在内存中,刷新需重登 */
  }
}

/** 清除令牌(登出/401 时调用)。 */
export function clearAuthToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/** 请求头:有令牌时带 Authorization: Bearer。 */
export function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * 给 URL 附加令牌 query(SSE/EventSource/`<img src>` 等无法带 header 的场景)。
 * 无令牌/已有 token 参数时原样返回。
 */
export function withAuthToken(url: string): string {
  const token = getAuthToken();
  if (!token || /[?&]token=/.test(url)) {
    return url;
  }
  return url + (url.includes("?") ? "&" : "?") + `token=${encodeURIComponent(token)}`;
}

/**
 * 401 统一处理:清令牌 + 跳登录页(带原地址回跳)。
 * 已在登录页时不重复跳(避免循环)。
 */
export function handleUnauthorized(): void {
  clearAuthToken();
  if (window.location.pathname === "/login") {
    return;
  }
  const back = encodeURIComponent(window.location.pathname + window.location.search);
  window.location.href = `/login?back=${back}`;
}

/** 是否已登录(有令牌;真实性由服务端校验)。 */
export function hasAuthToken(): boolean {
  return getAuthToken().length > 0;
}
