/**
 * 独立的延迟工具：避免轻量模块（如 chatApi）因导入 mockApi 而被动打包整个 faker。
 */
export const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
