'use client';

import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { usePreferences } from "@/hooks/usePreferences";
import { Search, ArrowUp, Loader2, Paperclip, BookOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
      <div className="relative w-full max-w-[12rem] md:w-64 hidden sm:block" onClick={openCmdK}>
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground w-4 h-4" />
        <Input readOnly placeholder="搜索..." className="pl-9 pr-4 py-1.5 h-8 bg-muted border-border text-xs focus-visible:ring-1 focus-visible:ring-blue-500 cursor-pointer w-full" />
        <div className="absolute right-2 top-1/2 transform -translate-y-1/2 hidden md:flex items-center gap-1 cursor-pointer">
          <kbd className="border border-border rounded px-1 text-[9px] text-muted-foreground bg-card">⌘K</kbd>
        </div>
      </div>
      <Button variant="ghost" size="icon" className="sm:hidden h-8 w-8 text-muted-foreground" onClick={openCmdK}>
        <Search className="w-4 h-4" />
      </Button>
    </>
  );

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "助手", isCurrent: true }]}
        actions={headerActions}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 relative">
        <div className="max-w-4xl mx-auto space-y-6 pb-20">
          {/* 首要焦点:输入需求 */}
          <div className="pt-6 sm:pt-10 animate-in fade-in slide-in-from-bottom-2">
            <h1 className="text-xl sm:text-2xl font-bold text-foreground mb-4">{greetLine}，今天想让我帮你做什么？</h1>
            <div className="bg-card border border-border rounded-2xl shadow-sm focus-within:border-blue-400 dark:focus-within:border-blue-600 transition-colors p-3">
              <textarea
                rows={2}
                value={demand}
                onChange={(e) => setDemand(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    startDemand();
                  }
                }}
                placeholder="例如：比较这几份资料的差异，给我一份报告；或把本周的演出照片整理到一个文件夹"
                className="w-full bg-transparent border-none outline-none resize-none text-sm text-foreground placeholder:text-muted-foreground/60 px-1 py-1"
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
              <div className="flex items-center justify-between mt-1">
                <div className="flex items-center gap-2 text-[10px] text-muted-foreground/70">
                  {/* B3:发送前可加资料——📎 文件 / 📄 知识库 */}
                  <button
                    type="button"
                    onClick={() => setPicker("file")}
                    className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border hover:bg-muted hover:text-foreground transition-colors cursor-pointer"
                    title="添加文件(随需求一起交给 AI)"
                  >
                    <Paperclip className="w-3 h-3" /> 文件
                  </button>
                  <button
                    type="button"
                    onClick={() => setPicker("doc")}
                    className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border hover:bg-muted hover:text-foreground transition-colors cursor-pointer"
                    title="添加知识库文档(随需求一起交给 AI)"
                  >
                    <BookOpen className="w-3 h-3" /> 知识库
                  </button>
                  <span className="hidden sm:inline">Enter 发送，Shift+Enter 换行</span>
                </div>
                <Button
                  size="sm"
                  className="h-7 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
                  onClick={startDemand}
                  disabled={!demand.trim() || starting}
                >
                  {starting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowUp className="w-3.5 h-3.5" />}
                </Button>
              </div>
            </div>
          </div>

          {/* 继续处理:后台运行中的轮次(真实探测,无则不显示空卡片) */}
          <section className="animate-in fade-in slide-in-from-bottom-3 duration-500">
            <h2 className="text-xs font-bold text-muted-foreground uppercase tracking-wide mb-2">继续处理</h2>
            <RunningTasks compact />
          </section>

          {/* 最近任务结果(B1 命名修正 2026-09-27:内容是执行记录,含失败/取消,
              不叫「成果」;真正的已保存文件在「资料 → 工作区/长期知识」) */}
          <section className="animate-in fade-in slide-in-from-bottom-3 duration-500">
            <h2 className="text-xs font-bold text-muted-foreground uppercase tracking-wide mb-2">最近任务结果</h2>
            <RecentResults />
          </section>

          {/* 常用操作 */}
          <section className="animate-in fade-in slide-in-from-bottom-4 duration-700">
            <h2 className="text-xs font-bold text-muted-foreground uppercase tracking-wide mb-2">常用操作</h2>
            <QuickActions />
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
