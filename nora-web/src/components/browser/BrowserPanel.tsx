import { useEffect, useState } from "react";
import { ArrowLeft, ArrowRight, Download, ExternalLink, Globe, Hand, Loader2, Plus, RotateCw, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useBrowser } from "@/hooks/useBrowser";
import { useFileViewer } from "@/hooks/useFileViewer";
import { BrowserViewport } from "./BrowserViewport";

type Browser = ReturnType<typeof useBrowser>;

export default function BrowserPanel({ sessionId, browser }: { sessionId: string; browser: Browser }) {
  const { state, busy, error, act } = browser;
  const [address, setAddress] = useState("");
  const current = state?.tabs.find(tab => tab.id === state.activeTabId);
  const human = state?.owner === "human";
  const enabled = !!human && !busy;
  useEffect(() => { setAddress(current?.url === "about:blank" ? "" : current?.url ?? ""); }, [current?.url]);
  const openFile = (target: string) => void useFileViewer.getState().openTargets([target], target, { sessionId });
  const navigate = (newTab = false) => {
    const raw = address.trim();
    if (raw) void act({ action: "open", url: /^https?:\/\//i.test(raw) ? raw : `https://${raw}`, newTab });
  };
  return <section aria-label="内置浏览器" className="h-full min-h-0 flex flex-col bg-card">
    <form className="shrink-0 flex items-center gap-1 p-2 border-b" onSubmit={event => { event.preventDefault(); navigate(); }}>
      {([{ action: "back", label: "后退", Icon: ArrowLeft }, { action: "forward", label: "前进", Icon: ArrowRight }, { action: "reload", label: "刷新网页", Icon: RotateCw }]).map(({ action, label, Icon }) =>
        <Button key={action} type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" title={label} disabled={!enabled}
          onClick={() => void act({ action })}><Icon className="w-3.5 h-3.5" /></Button>)}
      <input aria-label="网页地址" type="text" value={address} onChange={event => setAddress(event.target.value)}
        readOnly={!!state?.active && !human} placeholder="输入网页地址，例如 https://playwright.dev"
        className="min-w-0 flex-1 rounded-md bg-muted px-2 py-1.5 text-base sm:text-xs outline-none focus:ring-1 focus:ring-blue-500" />
      <Button type="submit" variant="outline" size="sm" className="h-7 px-2 text-[11px]" disabled={busy || (!!state?.active && !human) || !address.trim()}>打开</Button>
      <Button type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" aria-label="在新标签打开地址栏网址" title="在新标签打开地址栏网址" disabled={busy || (!!state?.active && !human) || !address.trim()} onClick={() => navigate(true)}><Plus className="w-3.5 h-3.5" /></Button>
      {state?.active && <Button type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" aria-label={human ? "交给 Agent" : "接管浏览器"} title={human ? "交给 Agent" : "接管浏览器"} disabled={busy}
        onClick={() => void act({ action: human ? "release" : "takeover" })}><Hand className={`w-3.5 h-3.5 ${human ? "text-blue-500" : ""}`} /></Button>}
    </form>
    {error && <div role="alert" className="px-3 py-2 text-xs text-red-600 bg-red-50 dark:bg-red-950/20 border-b">
      <div className="flex gap-2 items-center">{error.message}<button type="button" className="ml-auto hover:underline" onClick={() => void browser.refresh()}>刷新状态与画面</button></div>
      {error.hint && <p className="mt-1 text-[10px]">{error.hint}</p>}
      <details className="mt-1 text-[10px]"><summary className="cursor-pointer">错误详情</summary><pre className="whitespace-pre-wrap break-all">{error.raw}</pre></details>
    </div>}
    {busy && <div className="shrink-0 px-3 py-1 flex gap-1.5 items-center text-[10px] text-blue-500"><Loader2 className="w-3 h-3 animate-spin" />正在操作网页…</div>}
    {state?.active ? <BrowserViewport sessionId={sessionId} state={state} frame={browser.frame} enabled={!!human}
      act={act} onError={browser.frameError} onLoaded={browser.frameLoaded} /> : <div className="flex-1 flex flex-col items-center justify-center gap-3 p-6 text-center text-muted-foreground">
      <Globe className="w-10 h-10 opacity-30" />
      <p className="text-sm">你和 Agent 共用的浏览器</p>
      <p className="text-xs max-w-[300px] leading-relaxed">输入地址打开真实网页，或让 Agent 收集资料。支持点击、输入、多标签和文件导出，随时可接管。</p>
      <p className="text-[10px] opacity-70">独立浏览器会话，登录状态在关闭时保存</p>
    </div>}
    {!!state?.exports.length && <details className="shrink-0 border-t text-xs" open>
      <summary className="px-3 py-2 cursor-pointer text-muted-foreground">导出与验收 · {state.exports.length}</summary>
      <div className="max-h-32 overflow-auto px-3 pb-2 space-y-2">
        {state.exports.map(item => <div key={item.id} className="flex flex-wrap gap-2 items-center">
          <ShieldCheck className={`w-3.5 h-3.5 ${item.verified ? "text-emerald-500" : "text-amber-500"}`} />
          <span title={item.checks.join("；")} className="text-[10px]">{item.rowCount} 行 · {item.verified ? "上次核验通过" : "需要检查"}</span>
          <button type="button" className="inline-flex gap-1 items-center text-blue-500 hover:underline" onClick={() => openFile(item.csvTarget)}><Download className="w-3 h-3" />CSV</button>
          <button type="button" className="text-blue-500 hover:underline" onClick={() => openFile(item.reportTarget)}>验收单</button>
          <button type="button" className="text-muted-foreground hover:underline" onClick={() => void browser.verify(item.id)}>重新核验</button>
        </div>)}
      </div>
    </details>}
    {!!state?.downloads.length && <details className="shrink-0 border-t text-xs">
      <summary className="px-3 py-2 cursor-pointer text-muted-foreground">网页下载 · {state.downloads.length}</summary>
      <div className="max-h-24 overflow-auto px-3 pb-2 space-y-1">{state.downloads.map(item => <button key={item.target} type="button" className="block text-blue-500 hover:underline" onClick={() => openFile(item.target)}>{item.name}</button>)}</div>
    </details>}
    {state?.active && <footer className="shrink-0 px-3 py-1.5 flex items-center gap-3 border-t text-[10px] text-muted-foreground">
      <span>{human ? "你正在操作" : "Agent 可以操作"} · 当前对话</span>
      {current?.url.startsWith("http") && <a href={current.url} target="_blank" rel="noreferrer" className="ml-auto flex items-center gap-1"><ExternalLink className="w-3 h-3" />外部打开</a>}
      <button type="button" disabled={!enabled} onClick={() => void act({ action: "close" })} className="hover:text-red-500 disabled:opacity-30">关闭会话</button>
    </footer>}
  </section>;
}
