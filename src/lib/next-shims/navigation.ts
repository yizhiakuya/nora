/**
 * React Router shim：让原有 `next/navigation` / `next/link` / `next/image` 调用方
 * 在 Vite SPA 中无需改动即可工作。后续可逐步替换为直接 import react-router-dom。
 */
import { useNavigate as useRRNavigate } from "react-router-dom";

export function useRouter() {
  const navigate = useRRNavigate();
  return {
    push: (href: string) => navigate(href),
    replace: (href: string) => navigate(href, { replace: true }),
    back: () => window.history.back(),
    forward: () => window.history.forward(),
    refresh: () => window.location.reload(),
    prefetch: () => {},
  };
}

export { usePathname } from "./usePathname";
export { redirect } from "./redirect";
