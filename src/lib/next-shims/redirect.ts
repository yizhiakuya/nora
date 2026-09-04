export function redirect(href: string): never {
  // SPA 环境下直接跳转
  window.location.href = href;
  throw new Error(`Redirect to ${href}`);
}
