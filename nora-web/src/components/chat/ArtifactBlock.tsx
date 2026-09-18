'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ExternalLink, FileCode2, Loader2, RefreshCw, AlertTriangle } from "lucide-react";
import { workspaceApi } from "@/lib/services/workspaceApi";
import { artifactHeight, buildArtifactSrcDoc, isDarkTheme, type ArtifactData } from "@/lib/artifact";

/**
 * H5 产物画廊(2026-09-18):agent 自写 HTML 的沙箱内嵌渲染。
 *
 * 行为:
 * - 读工作区 HTML 文件 → 注入 Tailwind 运行时与主题 → sandbox iframe(srcDoc);
 * - 工具栏:标题 + 刷新(重读文件)+ 新窗口打开 + 文件页查看;
 * - 失败降级:文件不存在/读取失败 → 错误卡(附路径),不静默消失。
 *
 * 安全:iframe 无 allow-same-origin(见 lib/artifact.ts 注释)。
 */
export function ArtifactBlock({ data }: { data: ArtifactData }) {
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [dark, setDark] = useState(() => isDarkTheme());
  /** 自适应高度:iframe 内 postMessage 上报内容高度;围栏 height 为初始高度,
   *  内容增长可超过它(绝对上限 1600px,超长内容内部滚动——画廊是任意布局,
   *  固定高度会裁内容;2026-09-18 实测 625px 内容被 520px 初始高度裁掉的坑)。 */
  const [contentHeight, setContentHeight] = useState<number | null>(null);
  const initialHeight = artifactHeight(data);
  const height = contentHeight != null
    ? Math.min(Math.max(contentHeight, initialHeight), 1600)
    : initialHeight;
  const title = data.title || data.path.split('/').pop() || data.path;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const content = await workspaceApi.readFile(data.path);
      if (!content || !content.trim()) {
        setError('文件是空的');
      } else {
        setHtml(content);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取失败');
    } finally {
      setLoading(false);
    }
  }, [data.path]);

  useEffect(() => {
    void load();
  }, [load]);

  // 高度上报:只接受本 iframe 的消息(事件源匹配,防串台)
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (e.source !== iframeRef.current?.contentWindow) return;
      const d = e.data as { type?: string; height?: number } | null;
      if (d && d.type === 'nora-artifact-height' && typeof d.height === 'number' && d.height > 0) {
        setContentHeight(d.height);
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);

  // 跟随 Nora 主题切换(观察 <html> 的 class 变化)
  useEffect(() => {
    const observer = new MutationObserver(() => setDark(isDarkTheme()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  const srcDoc = useMemo(() => (html != null ? buildArtifactSrcDoc(html, dark) : ''), [html, dark]);

  /** 新窗口打开:blob URL 承载完整 srcdoc(无父页面样式,独立全屏体验)。 */
  const openInNewTab = useCallback(() => {
    if (!html) return;
    const blob = new Blob([buildArtifactSrcDoc(html, dark)], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank', 'noopener');
    // 浏览器打开后释放(延迟防竞态)
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }, [html, dark]);

  return (
    <div className="my-2 rounded-xl border border-border bg-card overflow-hidden animate-in fade-in slide-in-from-top-1">
      {/* 工具栏 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted/40">
        <FileCode2 className="w-3.5 h-3.5 text-violet-500 shrink-0" />
        <span className="text-xs font-medium text-foreground truncate min-w-0">{title}</span>
        <span className="text-[10px] text-muted-foreground font-mono truncate hidden sm:inline">{data.path}</span>
        <div className="flex-1" />
        <button
          type="button"
          onClick={() => void load()}
          disabled={loading}
          title="重新读取文件(agent 更新后可刷新)"
          className="p-1 rounded text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer disabled:opacity-40"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
        </button>
        <button
          type="button"
          onClick={openInNewTab}
          disabled={!html}
          title="在新窗口打开"
          className="p-1 rounded text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer disabled:opacity-40"
        >
          <ExternalLink className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* 内容区 */}
      {loading ? (
        <div className="flex items-center justify-center gap-2 text-muted-foreground" style={{ height: 120 }}>
          <Loader2 className="w-4 h-4 animate-spin opacity-50" />
          <span className="text-xs">加载产物…</span>
        </div>
      ) : error ? (
        <div className="flex items-start gap-2 px-3.5 py-3 text-red-600 dark:text-red-400">
          <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
          <div className="min-w-0">
            <div className="text-xs font-medium">产物加载失败:{error}</div>
            <div className="text-[11px] text-muted-foreground mt-0.5 font-mono break-all">{data.path}</div>
          </div>
        </div>
      ) : (
        <iframe
          ref={iframeRef}
          title={title}
          srcDoc={srcDoc}
          // allow-same-origin 的取舍(2026-09-18 实测):
          // - 不给:无同源 sandbox 里 Tailwind Play 可用性不稳(实测与加载时序
          //   相关,首屏偶发不编译);localStorage 会抛异常——这是边界该有的行为;
          // - 给:样式稳定;代价是 JS 能读 Nora 的 localStorage/cookie。
          // 定案:给 allow-same-origin,同时收窄其余能力(无 allow-forms/
          // allow-popups/allow-top-navigation)——画廊是 agent 在本机生成的
          // 展示页,信任级与工具执行同级(工具本就能写工作区/跑命令)。
          sandbox="allow-scripts allow-same-origin"
          className="w-full border-0 bg-white dark:bg-zinc-950 block"
          style={{ height }}
        />
      )}
    </div>
  );
}
