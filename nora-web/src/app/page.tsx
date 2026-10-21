'use client';

import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { usePreferences } from "@/hooks/usePreferences";
import { ArrowUp, ArrowUpRight, Loader2, Paperclip, BookOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { QuickActions } from "@/components/home/QuickActions";
import { RunningTasks } from "@/components/automations/RunningTasks";
import { RecentResults } from "@/components/home/RecentResults";
import { useChatSessions } from "@/hooks/useChatSessions";
import { ReferencePicker } from "@/components/chat/ReferencePicker";
import { RefChip } from "@/components/chat/RefChip";
import { SessionProjectButton } from "@/components/chat/SessionProjectButton";
import { refKey, type ChatRef } from "@/lib/chatRefs";

/**
 * 助手首页(M1-02,2026-09-20,按产品改造方案 §4.1):
 * 输入需求为首要焦点;「继续处理」展示后台运行中的轮次;「最近任务结果」打开
 * 已完成的结果。不再是服务数/连接数/Chunks 的运维面板(审查报告 B10)。
 *
 * 开始新需求 = 新建会话后带 prompt 跳转,**在对话页自动发送**(2026-09-20 用户
 * 反馈修正:此前只预填不发送,用户以为"发不出去")。跳转携带 autosend=1,
 * 对话页发送后即从 URL 移除,刷新不会重发。
 *
 * B3(2026-09-27,评审报告):**发送前可以加资料**——此前首页只能发纯文本,
 * 需要资料的请求先开始执行、资料随后才补。现在输入区有 📎(文件)与 📄
 * (知识库)按钮,选中的引用随跳转带进新会话(chips 在输入区可见可删)。
 */
export default function Home() {
  // 问候语(2026-09-19 去假数据):按时段 + 用户昵称
  const accountName = usePreferences((s) => s.account.name);
  const hour = new Date().getHours();
  const greeting = hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
  const greetLine = accountName.trim() ? `${greeting}，${accountName.trim()}` : greeting;
  const [demand, setDemand] = useState("");
  const [starting, setStarting] = useState(false);
  /** 发送前选中的资料引用(B3):随跳转带进新会话,对话页解析为 chips */
  const [refs, setRefs] = useState<ChatRef[]>([]);
  const [picker, setPicker] = useState<"file" | "doc" | null>(null);
  const navigate = useNavigate();
  const createSession = useChatSessions((s) => s.createSession);
  const [draftSessionId, setDraftSessionId] = useState("");
  const homeSession = useChatSessions(state => state.sessions.find(session => session.id === draftSessionId));
  useEffect(() => {
    if (!homeSession) setDraftSessionId(createSession(false));
  }, [homeSession, createSession]);


  /** 开始新需求:创建会话,带输入内容跳转对话页并自动发送(autosend=1) */
  const startDemand = () => {
    const text = demand.trim();
    if (!text || starting || !homeSession) return;
    setStarting(true);
    const sessionId = homeSession.id;
    // B3:选中的资料引用随跳转带进新会话(对话页解析为 chips,发送时序列化)
    const refsParam = refs.length > 0 ? `&refs=${encodeURIComponent(JSON.stringify(refs))}` : "";
    navigate(`/chat?prompt=${encodeURIComponent(text)}&session=${encodeURIComponent(sessionId)}&autosend=1${refsParam}`);
  };

  return (
    <>
      <Header
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "助手", isCurrent: true }]}
      />

      <div className="page-content custom-scroll [container-type:inline-size]">
        <div className="content-enter max-w-[820px] mx-auto space-y-9 pb-10">
          {/* 首要焦点:输入需求 */}
          <div className="pt-1 sm:pt-5">
            <div className="mb-6 sm:mb-8">
              <p className="mb-3 flex items-center gap-2 text-[13px] text-muted-foreground"><span className="h-px w-5 bg-border" aria-hidden="true" />{greetLine}</p>
              <h1 className="text-[28px] sm:text-[36px] font-medium tracking-[-0.045em] leading-tight text-foreground">今天，想完成什么？</h1>
              <p className="mt-3 text-[13px] leading-relaxed text-muted-foreground">带上你的问题和资料，让 Nora 和你一起推进。</p>
            </div>
            <div className="composer p-3 sm:p-4">
              <textarea
                rows={4}
                aria-label="告诉 Nora 你想完成的事"
                value={demand}
                onChange={(e) => setDemand(e.target.value)}
                onKeyDown={(e) => {
                  if (e.nativeEvent.isComposing) return;
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    startDemand();
                  }
                }}
                placeholder="想做些什么？描述任务，或带上文件一起开始…"
                className="w-full bg-transparent border-none outline-none resize-none text-base sm:text-[15px] leading-relaxed text-foreground placeholder:text-muted-foreground/75 px-2 py-2"
              />
              {/* B3:发送前选中的资料 chips(可删;随跳转带进新会话) */}
              {refs.length > 0 && (
                <div className="flex flex-wrap gap-1.5 px-1 pb-1.5">
                  {refs.map((r) => (
                    <RefChip key={refKey(r)} chatRef={r}
                      onRemove={(k) => setRefs((prev) => prev.filter((x) => refKey(x) !== k))} />
                  ))}
                </div>
              )}
              <div className="flex items-center justify-between gap-3 mt-2">
                <div className="flex min-w-0 flex-wrap items-center gap-1 text-xs text-muted-foreground">
                  {/* B3:发送前可加资料——📎 文件 / 📄 知识库 */}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setPicker("file")}
                    className="gap-1.5 px-2 text-xs"
                    title="添加文件(随需求一起交给 AI)"
                  >
                    <Paperclip className="w-4 h-4" /> 文件
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setPicker("doc")}
                    className="gap-1.5 px-2 text-xs"
                    title="添加知识库文档(随需求一起交给 AI)"
                  >
                    <BookOpen className="w-4 h-4" /> 知识库
                  </Button>
                </div>
                <Button
                  size="icon"
                  variant="composer"
                  aria-label="发送需求"
                  aria-busy={starting}
                  className="h-9 w-9 shrink-0"
                  onClick={startDemand}
                  disabled={!demand.trim() || starting || !homeSession}
                >
                  {starting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowUp className="w-3.5 h-3.5" />}
                </Button>
              </div>
            </div>
            <div className="mt-2 flex min-w-0 items-center justify-between gap-2 px-1">
              {homeSession && <SessionProjectButton sessionId={homeSession.id} disabled={starting} />}
              <span className="hidden sm:block shrink-0 text-[11px] text-muted-foreground/70">Enter 发送 · Shift + Enter 换行</span>
            </div>
            <div className="mt-6"><h2 className="section-label mb-3">从这里开始</h2><QuickActions /></div>
          </div>

          {/* 常用操作 */}
          <div className="grid items-start gap-7 [@container(min-width:760px)]:grid-cols-2 border-t border-border/60 pt-7">
          <section className="min-w-0 min-h-32">
            <div className="mb-4 flex items-center justify-between"><h2 className="section-heading">继续处理</h2><Link to="/tasks?view=running" className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1">全部运行 <ArrowUpRight className="h-3 w-3" /></Link></div>
            <RunningTasks compact />
          </section>

          {/* 运行结果与已保存成果分别展示，均可回到来源会话。 */}
          <section className="min-w-0 min-h-64">
            <div className="mb-4 flex items-center justify-between"><h2 className="section-heading">最近任务结果</h2><Link to="/sessions" className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1">会话历史 <ArrowUpRight className="h-3 w-3" /></Link></div>
            <RecentResults />
          </section>
          </div>
        </div>
      </div>

      {/* B3:发送前加资料的选择器(文件/知识库) */}
      <ReferencePicker
        kind={picker ?? "file"}
        isOpen={picker != null}
        onClose={() => setPicker(null)}
        onPick={(ref) => {
          setRefs((prev) => (prev.some((r) => refKey(r) === refKey(ref)) ? prev : [...prev, ref]));
          setPicker(null);
        }}
      />
    </>
  );
}
