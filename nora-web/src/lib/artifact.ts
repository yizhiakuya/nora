/**
 * H5 产物画廊(2026-09-18):agent 自己写 HTML 展示页,聊天里沙箱内嵌渲染。
 *
 * 协议:AI 用 manage_workspace write 写一个 .html 文件,在最终回答里放围栏:
 *   ```nora-artifact
 *   {"path": "galleries/2026-09-18-相册整理.html", "title": "相册整理结果", "height": 520}
 *   ```
 * 前端读文件 → 注入 Tailwind Play 运行时 + 主题 → sandbox iframe 渲染。
 *
 * 安全模型(2026-09-18 实测定案):
 * - iframe sandbox="allow-scripts allow-same-origin"(其余能力全禁:无
 *   allow-forms/popups/top-navigation)——allow-same-origin 是 Tailwind Play
 *   稳定运行的实测需求(opaque origin 下编译时序不稳);
 * - 画廊是 agent 在本机生成的展示页,信任级与工具执行同级(工具本就能
 *   写工作区/跑命令),不扩大权限面;iframe 内调 /api/* 仍受 Nora 会话约束;
 * - Tailwind 运行时从同源 /tailwind-play.js 加载(本地托管,不走外网 CDN);
 * - 需要展示的数据必须**嵌进 HTML**(自包含原则,skill 里有约定)。
 */

export interface ArtifactData {
  /** 工作区内 HTML 文件路径(相对工作区根) */
  path: string;
  /** 卡片标题(省略用文件名) */
  title?: string;
  /** iframe 高度(px,默认 520,clamp 200-1200) */
  height?: number;
}

/** 从文本里提取 ```nora-artifact 围栏(无则 null)。 */
export function parseArtifactFence(content: string | null | undefined): ArtifactData | null {
  if (!content) return null;
  const m = content.match(/```nora-artifact\s*\n([\s\S]*?)```/);
  return m ? parseArtifactJson(m[1]) : null;
}

/** 解析围栏 JSON;结构不完整返回 null(调用方降级为普通代码块,不丢内容)。 */
export function parseArtifactJson(raw: string): ArtifactData | null {
  try {
    const data = JSON.parse(raw) as ArtifactData;
    if (!data || typeof data.path !== "string" || data.path.trim() === "") {
      return null;
    }
    return data;
  } catch {
    return null;
  }
}

/** 高度 clamp(防 AI 写离谱值)。 */
export function artifactHeight(data: ArtifactData): number {
  const h = data.height ?? 520;
  return Math.max(200, Math.min(1200, Math.round(h)));
}

/**
 * 构造沙箱 iframe 的 srcdoc:
 * - 注入 Tailwind Play 运行时(同源静态文件,HTTP 缓存复用);
 * - darkMode: 'class' + 按当前 Nora 主题给 <html> 挂 dark;
 * - 基础 reset + 中文字体栈 + 主题默认底色;
 * - 内容按自包含约定内联(数据/样式/脚本都在 userHtml 里);
 * - **高度上报**:内容尺寸变化时 postMessage 给宿主(ArtifactBlock 自适应高度,
 *   围栏 height 作为上限兜底——画廊是 AI 生成的任意布局,固定高度会裁内容)。
 */
export function buildArtifactSrcDoc(userHtml: string, dark: boolean): string {
  return `<!DOCTYPE html>
<html lang="zh-CN" class="${dark ? "dark" : ""}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Nora 产物</title>
<script src="/tailwind-play.js"><\/script>
<script>tailwind.config = { darkMode: "class" };<\/script>
<style>
  html, body { margin: 0; padding: 0; }
  body {
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    -webkit-font-smoothing: antialiased;
    /* 默认背景跟随主题:AI 没给根元素设 bg-* 时也保持正确底色 */
    background: #ffffff;
    color: #18181b;
  }
  .dark body { background: #09090b; color: #fafafa; }
</style>
</head>
<body>
${userHtml}
<script>
  // 高度上报:内容尺寸变化 → postMessage 给宿主(自动高度;父级 clamp 上限)
  (function () {
    var last = 0;
    function report() {
      var h = Math.max(
        document.body.scrollHeight,
        document.documentElement.scrollHeight,
        document.body.offsetHeight
      );
      if (h !== last) {
        last = h;
        parent.postMessage({ type: "nora-artifact-height", height: h }, "*");
      }
    }
    // Tailwind Play 异步编译 → 用 ResizeObserver 持续跟随,而非一次性测量
    if (window.ResizeObserver) {
      new ResizeObserver(report).observe(document.documentElement);
    }
    window.addEventListener("load", report);
    setTimeout(report, 300);
    setTimeout(report, 1200);
  })();
<\/script>
</body>
</html>`;
}

/** 当前 Nora 主题(供构造 srcdoc 时挂 dark class)。 */
export function isDarkTheme(): boolean {
  if (typeof document === "undefined") return false;
  return document.documentElement.classList.contains("dark");
}
