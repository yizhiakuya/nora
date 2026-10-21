import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { History, Search } from "lucide-react";
import { Header } from "@/components/layout/Header";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { searchSessions, type SessionPage } from "@/lib/api/agentApi";
import { relativeTime } from "@/lib/relativeTime";

const PAGE_SIZE = 30;

export default function SessionsPage() {
  const [params, setParams] = useSearchParams();
  const query = params.get("q") ?? "";
  const offset = Math.max(0, Number(params.get("offset")) || 0);
  const [input, setInput] = useState(query);
  const [page, setPage] = useState<SessionPage>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => { setInput(query); }, [query]);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true); setError(undefined); setPage(undefined);
    void searchSessions(query, offset, PAGE_SIZE, abort.signal).then(value => { if (!abort.signal.aborted) setPage(value); })
      .catch(failure => { if (!abort.signal.aborted) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [query, offset, revision]);
  const changePage = (next: number) => setParams({ q: query, offset: String(next) });
  return <>
    <Header breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "全部会话", isCurrent: true }]} />
    <div className="page-content custom-scroll">
      <div className="max-w-[920px] mx-auto space-y-7">
        <div><h1 className="page-heading">全部会话</h1>
          <p className="page-description">查找以前交给 Nora 的工作，继续处理或查看结果。</p></div>
        <form className="flex gap-2" onSubmit={event => { event.preventDefault(); setParams({ q: input.trim() }); setRevision(value => value + 1); }}>
          <Input aria-label="搜索会话标题、ID 或消息正文" placeholder="搜索标题、ID 或消息正文…" maxLength={200} value={input} onChange={event => setInput(event.target.value)} className="text-base sm:text-sm" />
          <Button type="submit" variant="outline"><Search className="h-4 w-4" />搜索</Button>
        </form>
        {error && <div role="alert" className="surface p-4 space-y-3"><p className="whitespace-pre-wrap break-words text-sm">{error}</p><Button variant="outline" onClick={() => setRevision(value => value + 1)}>重试</Button></div>}
        {loading && <p role="status" className="text-sm text-muted-foreground">正在加载会话…</p>}
        {page && <>
          {page.items.length === 0 ? <p className="surface p-8 text-sm text-muted-foreground text-center">{query ? "没有匹配的会话，试试其他关键词。" : "还没有会话，从首页描述目标即可开始。"}</p>
            : <div className="work-list">{page.items.map(session => <Link key={session.id} to={`/chat?session=${encodeURIComponent(session.id)}`} className="work-row !py-4">
              <History className="h-4 w-4 shrink-0 text-muted-foreground/65" /><div className="min-w-0 flex-1"><div className="text-[13px] font-medium break-words">{session.title || "新对话"}</div>
              <div className="text-[11px] text-muted-foreground mt-1">{session.origin === "automation" ? "自动任务" : "对话"} · {session.messageCount} 条消息</div></div><span className="shrink-0 text-[11px] text-muted-foreground tabular-nums">{relativeTime(new Date(session.lastActivity ?? session.createdAt).getTime())}</span>
            </Link>)}</div>}
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <Button variant="outline" disabled={offset === 0} onClick={() => changePage(Math.max(0, offset - PAGE_SIZE))}>上一页</Button>
            <span>第 {Math.floor(offset / PAGE_SIZE) + 1} 页</span>
            <Button variant="outline" disabled={!page.hasMore} onClick={() => changePage(offset + PAGE_SIZE)}>下一页</Button>
          </div>
        </>}
      </div>
    </div>
  </>;
}
