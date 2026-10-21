'use client';

import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  MessageSquare, Folder, ListCheck, Settings, Database, Server,
  X, PanelLeftClose, PanelLeftOpen, Plus, Trash2, Bot, History, Search
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useEffect, useRef } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { useSidebarStore } from "@/hooks/useSidebar";
import { useChatSessions } from "@/hooks/useChatSessions";
import { useCommandPalette } from "@/hooks/useCommandPalette";
import { useDeleteChatSession } from "@/hooks/useDeleteChatSession";
import { relativeTime } from "@/lib/relativeTime";

/**
 * 主导航(2026-09-20,按产品改造方案 §3.1 + 用户反馈):
 * 「助手 / 资料 / 任务 / 数据源 / 环境 / 设置」——此前技术模块平铺为十个
 * 顶级入口,用户开始一件事之前要先学系统结构(审查报告 B01);
 * 数据源与环境控制台从底部小链接提升为并列主导航(用户明确要求,2026-09-20)。
 *
 * 图标风格约定(2026-09-30 用户反馈):导航栏统一线性图标(lucide),
 * 不放彩色品牌标(突兀);品牌标只在侧栏顶部/登录页/聊天头像出现。
 */
const NAV_ITEMS = [
  { name: "助手", icon: MessageSquare, href: "/" },
  { name: "全部会话", icon: History, href: "/sessions" },
  { name: "资料", icon: Folder, href: "/files" },
  { name: "任务", icon: ListCheck, href: "/tasks" },
  { name: "数据源", icon: Database, href: "/data-sources" },
  { name: "环境", icon: Server, href: "/environments" },
  { name: "Agent", icon: Bot, href: "/agent" },
];

/** 激活判定:多项共享前缀时取最长匹配(href="/" 仅精确匹配)。 */
function isNavActive(pathname: string, href: string): boolean {
  if (href === "/") return pathname === "/" || pathname.startsWith("/chat");
  return pathname === href || pathname.startsWith(href + "/") || pathname.startsWith(href + "?");
}

export function Sidebar() {
  const location = useLocation();
  const pathname = location.pathname;
  const navigate = useNavigate();
  const openSearch = useCommandPalette(s => s.open);

  const { isOpen, setIsOpen, isCollapsed, toggleCollapsed } = useSidebarStore();
  const opener = useRef<HTMLElement | null>(null);

  // Chat sessions
  const sessions = useChatSessions((s) => s.sessions);
  const activeId = useChatSessions((s) => s.activeId);
  const setActive = useChatSessions((s) => s.setActive);
  const createSession = useChatSessions((s) => s.createSession);
  const syncSessions = useChatSessions((s) => s.syncFromBackend);
  const handleDeleteSession = useDeleteChatSession();
  // 会话列表以后端为准:侧栏挂载时同步一次(失败静默用本地缓存)
  useEffect(() => {
    void syncSessions();
  }, [syncSessions]);


  useEffect(() => { setIsOpen(false); }, [pathname, setIsOpen]);
  useEffect(() => {
    const desktop = window.matchMedia("(min-width: 768px)");
    const closeOnDesktop = () => { if (desktop.matches) setIsOpen(false); };
    desktop.addEventListener("change", closeOnDesktop);
    return () => desktop.removeEventListener("change", closeOnDesktop);
  }, [setIsOpen]);

  const recentSessions = sessions.slice(0, 8);

  const content = (collapsed: boolean) => (
    <>
        <div className={cn("flex shrink-0 items-center", collapsed ? "flex-col gap-2 px-2 py-3" : "h-14 justify-between px-5")}>
          <Link to="/" onClick={() => setIsOpen(false)} className="flex min-w-0 items-center gap-2.5 rounded-lg" aria-label="Nora 首页">
            <img src="/brand-logo.png" alt="" width={24} height={24} className="w-6 h-6 shrink-0" />
            {!collapsed && <span className="text-[17px] font-semibold tracking-[-0.04em] text-foreground">Nora<span className="ml-2 text-[10px] font-normal tracking-normal text-muted-foreground">Workspace</span></span>}
          </Link>
          <Button variant="ghost" size="icon" className="hidden md:flex h-7 w-7 text-muted-foreground" onClick={toggleCollapsed} aria-label={collapsed ? "展开导航" : "收起导航"} title={collapsed ? "展开导航" : "收起导航"}>
            {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
          </Button>
          <Button variant="ghost" size="icon" aria-label="关闭导航" className="md:hidden h-8 w-8 text-muted-foreground" onClick={() => setIsOpen(false)}><X /></Button>
        </div>

        <div className="shrink-0 space-y-1 px-3 pb-4 pt-1">
          <Button variant="outline" className={cn("w-full border-input/70 bg-card h-9", collapsed ? "px-0" : "justify-start px-3")} aria-label="新建对话" title={collapsed ? "新建对话" : undefined} onClick={() => {
            const id = createSession();
            navigate(`/chat?session=${encodeURIComponent(id)}`);
            setIsOpen(false);
          }}>
            <Plus />{!collapsed && "新建对话"}
          </Button>
          <Button variant="ghost" className={cn("w-full h-8 text-muted-foreground", collapsed ? "px-0" : "justify-start px-3")} aria-label="搜索工作台" title="搜索工作台 · Ctrl/⌘ K" onClick={openSearch}>
            <Search />{!collapsed && <><span className="flex-1 text-left">搜索</span><kbd className="text-[10px] font-sans text-muted-foreground/70">Ctrl K</kbd></>}
          </Button>
        </div>

        <nav aria-label="主导航" className="shrink-0 space-y-0.5 px-3 pb-5">
          {NAV_ITEMS.map(({ name, icon: Icon, href }) => {
            const active = isNavActive(pathname, href);
            return <Link key={href} to={href} onClick={() => setIsOpen(false)} aria-current={active ? "page" : undefined} aria-label={name} title={collapsed ? name : undefined} className={cn(
              "flex h-9 items-center gap-2.5 rounded-lg px-3 text-[13px] transition-colors",
              collapsed && "justify-center px-0",
              active ? "bg-card text-foreground font-medium shadow-surface ring-1 ring-border/60" : "text-muted-foreground hover:bg-accent/70 hover:text-foreground"
            )}><Icon className="h-4 w-4 shrink-0" />{!collapsed && name}</Link>;
          })}
        </nav>

        {!collapsed && <div className="flex min-h-0 flex-1 flex-col px-3">
          <div className="flex items-center justify-between px-3 section-label"><span>最近对话</span><Link to="/sessions" onClick={() => setIsOpen(false)} title="查看全部会话" aria-label="查看全部会话" className="rounded p-1 hover:bg-accent hover:text-foreground"><History className="h-3 w-3" /></Link></div>
          <div className="min-h-0 flex-1 overflow-y-auto custom-scroll space-y-1 py-2">
            {recentSessions.length === 0 && <p className="px-3 py-3 text-xs leading-relaxed text-muted-foreground">开始一个对话，最近的工作会保存在这里。</p>}
            {recentSessions.map(session => {
              const active = activeId === session.id && pathname.startsWith("/chat");
              return <div key={session.id} className="group/session relative">
                <Link to={`/chat?session=${encodeURIComponent(session.id)}`} onClick={() => { setActive(session.id); setIsOpen(false); }} aria-current={active ? "page" : undefined} title={session.title} className={cn(
                  "block rounded-md px-3 py-2 pr-8 transition-colors",
                  active ? "bg-card text-foreground shadow-surface" : "text-muted-foreground hover:bg-accent hover:text-foreground"
                )}>
                  <div className="flex items-center gap-1.5 text-[13px]">
                    {session.origin === "automation" && <span className="shrink-0 rounded bg-muted px-1 py-0.5 text-[10px] font-medium text-muted-foreground">定时</span>}
                    <span className="truncate">{session.title}</span>
                  </div>
                  <div className="mt-0.5 truncate text-[11px] tabular-nums text-muted-foreground/75">
                    {relativeTime(session.updatedAt)}
                  </div>
                </Link>
                <button type="button" aria-label={`删除会话：${session.title}`} title="删除会话" onClick={() => handleDeleteSession(session.id)} className="absolute right-1 top-1/2 -translate-y-1/2 rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors md:opacity-0 md:group-hover/session:opacity-100 md:group-focus-within/session:opacity-100">
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>;
            })}
          </div>
        </div>}
        {collapsed && <div className="flex-1" />}

        <div className="shrink-0 p-3">
          <Link to="/settings" onClick={() => setIsOpen(false)} aria-label="设置" aria-current={pathname.startsWith("/settings") ? "page" : undefined} title={collapsed ? "设置" : undefined} className={cn(
            "flex h-9 items-center gap-2.5 rounded-md px-3 text-[13px] transition-colors",
            collapsed && "justify-center px-0",
            pathname.startsWith("/settings") ? "bg-card text-foreground shadow-surface" : "text-muted-foreground hover:bg-accent hover:text-foreground"
          )}><Settings className="h-4 w-4 shrink-0" />{!collapsed && "设置"}</Link>
        </div>
    </>
  );
  return <>
    <aside aria-label="工作台导航" className={cn("hidden md:flex flex-col shrink-0 h-full overflow-hidden bg-sidebar", isCollapsed ? "w-16" : "w-[224px]")}>
      {content(isCollapsed)}
    </aside>
    <Dialog.Root open={isOpen} onOpenChange={setIsOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="motion-overlay fixed inset-0 z-40 bg-black/30 md:hidden" />
        <Dialog.Content id="workbench-navigation" aria-describedby={undefined}
          className="motion-drawer fixed inset-y-0 left-0 z-50 flex w-[280px] max-w-[85vw] flex-col overflow-hidden bg-sidebar shadow-float outline-none md:hidden"
          onOpenAutoFocus={() => { opener.current = document.activeElement as HTMLElement | null; }}
          onCloseAutoFocus={event => {
            if (opener.current?.isConnected) { event.preventDefault(); opener.current.focus({ preventScroll: true }); }
          }}>
          <Dialog.Title className="sr-only">工作台导航</Dialog.Title>
          {content(false)}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  </>;
}
