'use client';

import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  MessageSquare, Folder, ListCheck, Settings, Database, Server,
  X, PanelLeftClose, PanelLeftOpen, Plus, Trash2, Bot
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useEffect } from "react";
import { useSidebarStore } from "@/hooks/useSidebar";
import { useChatSessions } from "@/hooks/useChatSessions";
import { deleteSessionOnBackend } from "@/lib/api/agentApi";
import { relativeTime } from "@/lib/relativeTime";
import { toast } from "sonner";

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

  const { isOpen, setIsOpen, isCollapsed, toggleCollapsed } = useSidebarStore();

  // Chat sessions
  const sessions = useChatSessions((s) => s.sessions);
  const activeId = useChatSessions((s) => s.activeId);
  const setActive = useChatSessions((s) => s.setActive);
  const createSession = useChatSessions((s) => s.createSession);
  const syncSessions = useChatSessions((s) => s.syncFromBackend);
  /** 会话删除 + undo(调研:单项低频破坏性操作用 undo toast,不用确认弹窗) */
  const deleteSession = useChatSessions((s) => s.deleteSession);
  const undoDeleteSession = useChatSessions((s) => s.undoDeleteSession);

  const handleDeleteSession = (id: string, title: string) => {
    const snapshot = useChatSessions.getState().sessions;
    const index = snapshot.findIndex((s) => s.id === id);
    const removed = snapshot[index];
    deleteSession(id);
    toast.success(`已删除「${title.slice(0, 16)}${title.length > 16 ? "…" : ""}」`, {
      description: "5 秒内可撤销",
      action: {
        label: "撤销",
        onClick: () => {
          if (removed) undoDeleteSession(removed, index);
        },
      },
      duration: 5000,
    });
    // undo 窗口结束后再删后端(延迟到 toast 关闭)
    window.setTimeout(() => {
      const stillDeleted = !useChatSessions.getState().sessions.some((s) => s.id === id);
      if (stillDeleted) void deleteSessionOnBackend(id).catch(error => {
        if (removed) useChatSessions.getState().undoDeleteSession(removed, index);
        toast.error(error instanceof Error ? error.message : String(error));
      });
    }, 5300);
  };
  // 会话列表以后端为准:侧栏挂载时同步一次(失败静默用本地缓存)
  useEffect(() => {
    void syncSessions();
  }, [syncSessions]);


  useEffect(() => { setIsOpen(false); }, [pathname, setIsOpen]);

  const collapsed = !isOpen && isCollapsed;
  const recentSessions = sessions.slice(0, 8);

  return (
    <>
      {isOpen && <button type="button" aria-label="关闭导航遮罩" className="fixed inset-0 bg-slate-950/35 z-30 md:hidden" onClick={() => setIsOpen(false)} />}
      <aside id="workbench-navigation" aria-label="工作台导航" onKeyDown={(e) => { if (e.key === "Escape") setIsOpen(false); }} className={cn(
        "bg-sidebar border-r border-border/80 flex flex-col shrink-0 z-40 h-full overflow-hidden transition-[width,transform] duration-200",
        "fixed md:relative top-0 bottom-0 left-0",
        collapsed ? "w-16" : "w-[232px]",
        isOpen ? "visible translate-x-0" : "invisible -translate-x-full md:visible md:translate-x-0"
      )}>
        <div className={cn("flex shrink-0 items-center", collapsed ? "flex-col gap-2 px-2 py-3" : "h-14 justify-between px-4")}>
          <Link to="/" className="flex min-w-0 items-center gap-2.5 rounded-lg" aria-label="Nora 首页">
            <img src="/brand-logo.png" alt="" width={28} height={28} className="w-7 h-7 shrink-0" />
            {!collapsed && <span className="text-base font-semibold tracking-tight text-foreground">Nora</span>}
          </Link>
          <Button variant="ghost" size="icon" className="hidden md:flex h-7 w-7 text-muted-foreground" onClick={toggleCollapsed} aria-label={collapsed ? "展开导航" : "收起导航"} title={collapsed ? "展开导航" : "收起导航"}>
            {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
          </Button>
          <Button variant="ghost" size="icon" aria-label="关闭导航" className="md:hidden h-8 w-8 text-muted-foreground" onClick={() => setIsOpen(false)}><X /></Button>
        </div>

        <div className="shrink-0 px-3 pb-3">
          <Button variant="outline" className={cn("w-full bg-card", collapsed ? "px-0" : "justify-start")} aria-label="新建对话" title={collapsed ? "新建对话" : undefined} onClick={() => {
            const id = createSession();
            navigate(`/chat?session=${encodeURIComponent(id)}`);
            setIsOpen(false);
          }}>
            <Plus />{!collapsed && "新建对话"}
          </Button>
        </div>

        <nav aria-label="主导航" className="shrink-0 space-y-1 px-3 pb-4">
          {NAV_ITEMS.map(({ name, icon: Icon, href }) => {
            const active = isNavActive(pathname, href);
            return <Link key={href} to={href} aria-current={active ? "page" : undefined} aria-label={name} title={collapsed ? name : undefined} className={cn(
              "flex h-10 items-center gap-3 rounded-lg px-3 text-[13px] font-medium transition-colors",
              collapsed && "justify-center px-0",
              active ? "bg-primary/[0.08] text-primary" : "text-muted-foreground hover:bg-accent hover:text-foreground"
            )}><Icon className="h-4 w-4 shrink-0" />{!collapsed && name}</Link>;
          })}
        </nav>

        {!collapsed && <div className="flex min-h-0 flex-1 flex-col border-t border-border/70 px-3 pt-4">
          <div className="px-3 text-xs font-medium text-muted-foreground">最近对话</div>
          <div className="min-h-0 flex-1 overflow-y-auto custom-scroll space-y-1 py-2">
            {recentSessions.length === 0 && <p className="px-3 py-3 text-xs leading-relaxed text-muted-foreground">开始一个对话，最近的工作会保存在这里。</p>}
            {recentSessions.map(session => {
              const active = activeId === session.id && pathname.startsWith("/chat");
              return <div key={session.id} className="group/session relative">
                <Link to={`/chat?session=${encodeURIComponent(session.id)}`} onClick={() => { setActive(session.id); setIsOpen(false); }} aria-current={active ? "page" : undefined} title={session.title} className={cn(
                  "block rounded-lg px-3 py-2.5 pr-9 transition-colors",
                  active ? "bg-card text-foreground shadow-surface" : "text-muted-foreground hover:bg-accent hover:text-foreground"
                )}>
                  <div className="flex items-center gap-1.5 text-[13px]">
                    {session.origin === "automation" && <span className="shrink-0 rounded bg-muted px-1 py-0.5 text-[10px] font-medium text-muted-foreground">定时</span>}
                    <span className="truncate">{session.title}</span>
                  </div>
                  <div className="mt-1 truncate text-[11px] tabular-nums text-muted-foreground">
                    {session.messageCount != null && session.messageCount > 0 && <span>{session.messageCount} 条 · </span>}{relativeTime(session.updatedAt)}
                  </div>
                </Link>
                <button type="button" aria-label={`删除会话：${session.title}`} title="删除会话" onClick={() => handleDeleteSession(session.id, session.title)} className="absolute right-1 top-1/2 -translate-y-1/2 rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors md:opacity-0 md:group-hover/session:opacity-100 md:group-focus-within/session:opacity-100">
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>;
            })}
          </div>
        </div>}
        {collapsed && <div className="flex-1" />}

        <div className="shrink-0 border-t border-border/70 p-3">
          <Link to="/settings" aria-label="设置" aria-current={pathname.startsWith("/settings") ? "page" : undefined} title={collapsed ? "设置" : undefined} className={cn(
            "flex h-10 items-center gap-3 rounded-lg px-3 text-[13px] font-medium transition-colors",
            collapsed && "justify-center px-0",
            pathname.startsWith("/settings") ? "bg-primary/[0.08] text-primary" : "text-muted-foreground hover:bg-accent hover:text-foreground"
          )}><Settings className="h-4 w-4 shrink-0" />{!collapsed && "设置"}</Link>
        </div>
      </aside>
    </>
  );
}
