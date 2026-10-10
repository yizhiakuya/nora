'use client';

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { usePreferences } from "@/hooks/usePreferences";
import { Search, ArrowUp, Loader2, Paperclip, BookOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCommandPalette } from "@/hooks/useCommandPalette";
import { QuickActions } from "@/components/home/QuickActions";
import { RunningTasks } from "@/components/automations/RunningTasks";
import { RecentResults } from "@/components/home/RecentResults";
import { useChatSessions } from "@/hooks/useChatSessions";
import { ReferencePicker } from "@/components/chat/ReferencePicker";
import { RefChip } from "@/components/chat/RefChip";
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

  // 全局搜索(Cmd+K)监听与面板实例已提到 RouteShell(§7 评审);
  // 首页搜索框点击打开同一实例
  const openCmdK = useCommandPalette((s) => s.open);

  /** 开始新需求:创建会话,带输入内容跳转对话页并自动发送(autosend=1) */
  const startDemand = () => {
    const text = demand.trim();
    if (!text || starting) return;
    setStarting(true);
    const sessionId = createSession();
    // B3:选中的资料引用随跳转带进新会话(对话页解析为 chips,发送时序列化)
    const refsParam = refs.length > 0 ? `&refs=${encodeURIComponent(JSON.stringify(refs))}` : "";
    navigate(`/chat?prompt=${encodeURIComponent(text)}&session=${encodeURIComponent(sessionId)}&autosend=1${refsParam}`);
  };

  const headerActions = (
    <>
      <Button variant="outline" aria-label="打开快速查找" className="hidden sm:flex h-8 w-48 justify-start bg-muted/50 text-muted-foreground" onClick={openCmdK}>
        <Search className="w-4 h-4" />
        <span className="flex-1 text-left">搜索…</span>
        <kbd className="hidden md:inline rounded border border-border bg-card px-1 text-[11px]">⌘K</kbd>
      </Button>
      <Button variant="ghost" size="icon" aria-label="打开快速查找" className="sm:hidden h-8 w-8 text-muted-foreground" onClick={openCmdK}>
        <Search className="w-4 h-4" />
      </Button>
    </>
  );

  return (
    <>
      <Header
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "助手", isCurrent: true }]}
        actions={headerActions}
      />

      <div className="page-content custom-scroll">
        <div className="max-w-4xl mx-auto space-y-7 pb-10">
          {/* 首要焦点:输入需求 */}
          <div className="pt-2 sm:pt-5 animate-in fade-in duration-200">
            <p className="mb-2 text-sm text-muted-foreground">{greetLine}</p>
            <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight text-foreground">今天想完成什么？</h1>
            <p className="page-description mb-6">告诉 Nora 你的目标，或带上资料一起开始。</p>
            <div className="bg-card border border-input rounded-2xl shadow-surface focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/10 transition-colors p-4">
              <textarea
                rows={3}
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
                placeholder="描述你的目标，或添加资料后开始…"
                className="w-full bg-transparent border-none outline-none resize-none text-base sm:text-sm leading-relaxed text-foreground placeholder:text-muted-foreground px-1 py-1"
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
              <div className="flex items-center justify-between gap-3 mt-3">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  {/* B3:发送前可加资料——📎 文件 / 📄 知识库 */}
                  <button
                    type="button"
                    onClick={() => setPicker("file")}
                    className="inline-flex h-8 items-center gap-1.5 px-2 rounded-lg hover:bg-muted hover:text-foreground transition-colors cursor-pointer"
                    title="添加文件(随需求一起交给 AI)"
                  >
                    <Paperclip className="w-4 h-4" /> 文件
                  </button>
                  <button
                    type="button"
                    onClick={() => setPicker("doc")}
                    className="inline-flex h-8 items-center gap-1.5 px-2 rounded-lg hover:bg-muted hover:text-foreground transition-colors cursor-pointer"
                    title="添加知识库文档(随需求一起交给 AI)"
                  >
                    <BookOpen className="w-4 h-4" /> 知识库
                  </button>
                  <span className="hidden md:inline text-[11px]">Enter 发送 · Shift+Enter 换行</span>
                </div>
                <Button
                  size="icon"
                  aria-label="发送需求"
                  className="h-9 w-9 shrink-0"
                  onClick={startDemand}
                  disabled={!demand.trim() || starting}
                >
                  {starting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowUp className="w-3.5 h-3.5" />}
                </Button>
              </div>
            </div>
          </div>

          {/* 常用操作 */}
          <section className="animate-in fade-in duration-200">
            <h2 className="section-heading mb-3">常用操作</h2>
            <QuickActions />
          </section>

          {/* 继续处理:后台运行中的轮次(真实探测,无则不显示空卡片) */}
          <section className="animate-in fade-in duration-200">
            <h2 className="section-heading mb-3">继续处理</h2>
            <RunningTasks compact />
          </section>

          {/* 最近任务结果(B1 命名修正 2026-09-27:内容是执行记录,含失败/取消,
              不叫「成果」;真正的已保存文件在「资料 → 工作区/长期知识」) */}
          <section className="animate-in fade-in duration-200">
            <h2 className="section-heading mb-3">最近任务结果</h2>
            <RecentResults />
          </section>


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
