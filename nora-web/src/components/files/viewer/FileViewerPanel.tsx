import { useEffect, useRef } from "react";
import { ArrowLeft, ChevronLeft, ChevronRight, FileText, Maximize2, Minimize2, X } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { ViewerContent } from "./ViewerContent";
import { ViewerToolbar } from "./ViewerToolbar";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import { PreviewSkeleton } from "./preview/PreviewSkeleton";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";

export default function FileViewerPanel() {
  const viewer = useFileViewer();
  const panelRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const scrollPositions = useRef(new Map<string, number>());
  useEffect(() => {
    if (viewer.status === "ready") contentRef.current?.scrollTo(0, scrollPositions.current.get(viewer.active?.target ?? "") ?? 0);
  }, [viewer.active?.target, viewer.status]);
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (!panelRef.current?.contains(document.activeElement)) return;
      const target = event.target as HTMLElement;
      if (target.matches("input, textarea, select, video, audio") || target.isContentEditable) return;
      if (event.key === "Escape") { event.preventDefault(); if (viewer.expanded) viewer.setExpanded(false); else viewer.close(); }
      if ((event.key === "ArrowLeft" || event.key === "ArrowRight") && !target.closest('[role="tablist"]') && !window.getSelection()?.toString()) { event.preventDefault(); viewer.navigate(event.key === "ArrowLeft" ? -1 : 1); }
    };
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  }, [viewer]);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (viewer.editing && viewer.draft !== viewer.preview?.text) { event.preventDefault(); event.returnValue = ""; }
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [viewer.editing, viewer.draft, viewer.preview?.text]);
  const collection = viewer.origin.collection ?? viewer.tabs.map(file => file.target);
  const index = viewer.origin.index ?? collection.indexOf(viewer.active?.target ?? "");
  const temporary = viewer.active?.target.startsWith("history:");
  return (
    <Tabs value={viewer.active?.target ?? ""} onValueChange={viewer.select} asChild><section ref={panelRef} aria-label="文件查看器" onPointerDownCapture={viewer.suppressAutoOpen} onKeyDownCapture={viewer.suppressAutoOpen} onWheelCapture={viewer.suppressAutoOpen} className="h-full min-h-0 min-w-0 flex flex-col bg-card border-l border-border text-foreground">
      <header className="shrink-0 px-4 pt-3">
        <div className="flex items-center justify-between gap-1 text-xs text-muted-foreground">
          <span>文件查看器{viewer.newFiles > 0 && <span className="ml-2 text-blue-600 dark:text-blue-400">新增 {viewer.newFiles} 个文件</span>}</span>
          <div className="flex items-center">
            {!viewer.wide && <Button size="sm" variant="ghost" onClick={viewer.close}><ArrowLeft className="w-3.5 h-3.5 mr-1" />返回{viewer.origin.sessionId ? "对话" : "原页面"}</Button>}
            {viewer.wide && <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => viewer.setExpanded(!viewer.expanded)} aria-label={viewer.expanded ? "恢复并排阅读" : "扩展阅读"}>{viewer.expanded ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}</Button>}
            <Button size="icon" variant="ghost" className="h-8 w-8" onClick={viewer.close} aria-label="关闭文件查看器"><X className="w-4 h-4" /></Button>
          </div>
        </div>
        <TabsList className="flex justify-start h-auto bg-transparent p-0 overflow-x-auto custom-scroll mt-2 gap-0.5" aria-label="已打开文件">
          {viewer.tabs.map(file => <div key={file.target} className={`flex items-center shrink-0 rounded-t-lg ${viewer.active?.target === file.target ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300 border-b-2 border-blue-500" : "text-muted-foreground"}`}>
            <TabsTrigger value={file.target} className="flex items-center gap-1.5 px-2 py-2.5 text-xs max-w-[220px] bg-transparent data-[state=active]:bg-transparent data-[state=active]:shadow-none"><FileText className="w-3.5 h-3.5 shrink-0" /><span className="truncate">{file.name}</span></TabsTrigger>
            <button type="button" aria-label={`关闭 ${file.name}`} onClick={() => viewer.closeTab(file.target)} className="p-1.5 mr-1 hover:bg-muted rounded-md"><X className="w-3 h-3" /></button>
          </div>)}
        </TabsList>
      </header>
      <div className="shrink-0 px-4 py-3 border-t border-border">
        <h2 className="text-sm font-medium break-all">{viewer.active?.name ?? "无法打开文件"}</h2>
        {viewer.active && <p className="mt-1 text-xs text-muted-foreground break-all" title={viewer.active.target}>{temporary ? "历史内容 · 尚未保存为文件" : viewer.active.target}{viewer.active.modifiedAt ? ` · ${new Date(viewer.active.modifiedAt).toLocaleString()} 更新` : ""}</p>}
      </div>
      <ViewerToolbar />
      {viewer.externalChange && <div className="shrink-0 p-3 text-xs bg-amber-50 dark:bg-amber-950/40 text-amber-800 dark:text-amber-200 flex items-center flex-wrap gap-2">文件可能已在外部修改，草稿已保留。<Button size="sm" variant="outline" onClick={() => viewer.requestAction(() => { void viewer.refresh(); })}>重新加载</Button><Button size="sm" variant="outline" onClick={() => void viewer.save(true)}>覆盖保存</Button></div>}
      <TabsContent ref={contentRef} value={viewer.active?.target ?? ""} aria-label={viewer.active?.name ?? "文件内容"} className="mt-0 flex-1 min-h-0 min-w-0 overflow-auto custom-scroll" onScroll={event => { if (viewer.status === "ready" && viewer.active) scrollPositions.current.set(viewer.active.target, event.currentTarget.scrollTop); }}>
        {viewer.status === "loading" ? <div className="p-5"><PreviewSkeleton /></div> : viewer.status === "error" ? <div role="alert" className="p-8 text-sm text-red-700 dark:text-red-300"><p>{viewer.error}</p><Button variant="outline" className="mt-3" onClick={() => void viewer.retry()}>重试</Button></div> : viewer.active && viewer.preview && (
          viewer.editing ? <textarea aria-label={`编辑 ${viewer.active.name}`} value={viewer.draft} onChange={event => viewer.setDraft(event.target.value)} spellCheck={false} className="w-full h-full min-h-[320px] p-5 font-mono text-sm bg-card text-foreground resize-none" />
            : <ViewerContent key={viewer.active.target} file={viewer.active} preview={viewer.preview} source={viewer.mode === "source"} />
        )}
      </TabsContent>
      <footer className="shrink-0 border-t border-border px-4 py-2 flex flex-wrap justify-between items-center gap-2 text-xs text-muted-foreground">
        <span>{temporary ? "临时只读视图" : viewer.editing ? "编辑中" : viewer.active?.delivery?.status === "failed" ? "文件已保存，成果登记失败" : "只读"}{viewer.preview?.truncated ? " · 内容已截断，下载查看完整文件" : ""}</span>
        {collection.length > 1 && index >= 0 && <div className="flex items-center gap-1"><Button variant="ghost" size="icon" className="h-7 w-7" disabled={index === 0} onClick={() => viewer.navigate(-1)} aria-label="上一个文件"><ChevronLeft className="w-3.5 h-3.5" /></Button><span>{index + 1} / {collection.length}</span><Button variant="ghost" size="icon" className="h-7 w-7" disabled={index === collection.length - 1} onClick={() => viewer.navigate(1)} aria-label="下一个文件"><ChevronRight className="w-3.5 h-3.5" /></Button></div>}
      </footer>
      <Modal isOpen={viewer.pendingAction != null} onClose={() => useFileViewer.setState({ pendingAction: null })} title="文件有未保存的修改" footer={<><Button variant="ghost" onClick={() => useFileViewer.setState({ pendingAction: null })}>继续编辑</Button><Button variant="outline" onClick={viewer.discardAndContinue} disabled={viewer.saving}>放弃修改</Button><Button onClick={() => { const action = viewer.pendingAction; void viewer.save().then(saved => { if (saved) { useFileViewer.setState({ pendingAction: null }); action?.(); } }); }} disabled={viewer.saving}>保存并继续</Button></>}><p className="text-sm text-muted-foreground">保存或放弃修改后，再切换文件或关闭查看器。</p></Modal>
    </section></Tabs>
  );
}
