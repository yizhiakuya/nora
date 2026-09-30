'use client';

import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  Sparkles, Folder, ListCheck, Settings, Database, Server,
  Check, X, PanelLeftClose, PanelLeftOpen, Plus, Trash2
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useEffect } from "react";
import { useSidebarStore } from "@/hooks/useSidebar";
import { useChatSessions } from "@/hooks/useChatSessions";
import { deleteSessionOnBackend } from "@/lib/api/agentApi";
import { relativeTime } from "@/lib/relativeTime";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "sonner";

/**
 * 主导航(2026-09-20,按产品改造方案 §3.1 + 用户反馈):
 * 「助手 / 资料 / 任务 / 数据源 / 环境 / 设置」——此前技术模块平铺为十个
 * 顶级入口,用户开始一件事之前要先学系统结构(审查报告 B01);
 * 数据源与环境控制台从底部小链接提升为并列主导航(用户明确要求,2026-09-20)。
 */
const NAV_ITEMS = [
  { name: "助手", icon: Sparkles, href: "/" },
  { name: "资料", icon: Folder, href: "/files" },
  { name: "任务", icon: ListCheck, href: "/tasks" },
  { name: "数据源", icon: Database, href: "/data-sources" },
  { name: "环境", icon: Server, href: "/environments" },
];

/** 激活判定:多项共享前缀时取最长匹配(href="/" 仅精确匹配)。 */
function isNavActive(pathname: string, href: string): boolean {
  if (href === "/") return pathname === "/" || pathname.startsWith("/chat");
  return pathname === href || pathname.startsWith(href + "/") || pathname.startsWith(href + "?");
}

export function Sidebar({ compact = false }: { compact?: boolean }) {
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
      if (stillDeleted) void deleteSessionOnBackend(id).catch(() => undefined);
    }, 5300);
  };
  // 会话列表以后端为准:侧栏挂载时同步一次(失败静默用本地缓存)
  useEffect(() => {
    void syncSessions();
  }, [syncSessions]);


  useEffect(() => { setIsOpen(false); }, [pathname, setIsOpen]);

  const collapsed = isCollapsed || compact;
  const recentSessions = sessions.slice(0, 8);

  return (
    <>
      {isOpen && <div className="fixed inset-0 bg-black/50 z-30 md:hidden animate-in fade-in" onClick={() => setIsOpen(false)} />}
      <aside className={cn("bg-card border-r border-border flex flex-col justify-between flex-shrink-0 z-40 h-full transition-all duration-300 ease-in-out overflow-hidden", "fixed md:relative top-0 bottom-0 left-0", collapsed ? "w-[72px] md:w-[72px]" : "w-[260px]", isOpen ? "translate-x-0" : "-translate-x-full md:translate-x-0")}>
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className={cn("pt-4 pb-2 shrink-0 flex items-center gap-1", collapsed ? "flex-col gap-1.5 px-2" : "px-3 justify-between")}>
              {!collapsed ? (
                /* 静态品牌区(2026-09-19 去假功能):此前是假工作区切换器
                   (Team Alpha 假切换/假"创建新工作区")——单用户单工作台,无此功能 */
                <div className="p-2.5 flex-1 flex items-center gap-3 min-w-0">
                  <img src="/logo-mark.png" alt="Nora" width={32} height={32} className="w-8 h-8 rounded-lg shrink-0" />
                  <span className="text-foreground font-bold text-sm truncate">Nora</span>
                </div>
              ) : (
                <img src="/logo-mark.png" alt="Nora" width={28} height={28} className="w-7 h-7 rounded-lg shrink-0" title="Nora" />
              )}
              <div className="flex items-center">
                {!compact && <button className="hidden md:flex p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-lg transition-colors shrink-0" onClick={toggleCollapsed} title={collapsed ? "展开导航" : "收起导航"}>
                  {collapsed ? <PanelLeftOpen className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
                </button>}
                <button className="md:hidden ml-1 p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-lg shrink-0" onClick={() => setIsOpen(false)}><X className="w-5 h-5" /></button>
              </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scroll sidebar-scroll px-2 sm:px-3 py-2">
            <nav className="space-y-1">
              {NAV_ITEMS.map((item) => {
                const isActive = isNavActive(pathname, item.href);
                return (
                  <div key={item.name} className="mb-0.5 flex flex-col">
                    <Link
                      to={item.href}
                      title={collapsed ? item.name : undefined}
                      className={cn("sidebar-item flex items-center rounded-lg text-sm font-medium transition-all group cursor-pointer", collapsed ? "justify-center px-2 py-2.5" : "px-3 py-2.5", isActive ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-muted-foreground hover:bg-muted hover:text-foreground")}
                    >
                      <div className={cn("flex items-center", collapsed ? "justify-center" : "gap-3")}><item.icon className={cn("w-4 h-4 shrink-0", isActive ? "text-blue-600 dark:text-blue-400" : "text-muted-foreground group-hover:text-foreground")} />{!collapsed && <span className="truncate">{item.name}</span>}</div>
                    </Link>

                    {/* 助手:最近会话子菜单 */}
                    {item.name === "助手" && !collapsed && (
                      <div className="ml-9 mt-1 mb-1 space-y-0.5 overflow-hidden animate-in slide-in-from-top-2 fade-in duration-200">
                        {recentSessions.map(session => {
                          const isSessionActive = activeId === session.id && pathname.startsWith("/chat");
                          return (
                            <div
                              key={session.id}
                              onClick={() => {
                                setActive(session.id);
                                if (pathname !== "/chat") navigate("/chat");
                              }}
                              className={cn(
                                "px-2 py-1.5 text-xs rounded-md cursor-pointer transition-colors group/session relative",
                                isSessionActive
                                  ? "bg-blue-50/50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-400 font-medium"
                                  : "text-muted-foreground hover:bg-muted hover:text-gray-900 dark:hover:text-gray-100"
                              )}
                              title={session.title}
                            >
                              <div className="truncate pr-5 flex items-center gap-1">
                                {/* 定时任务会话徽章(2026-09-20):定时器往会话发消息,
                                    用户在列表一眼区分人工会话与任务会话 */}
                                {session.origin === "automation" && (
                                  <span className="shrink-0 text-[9px] font-bold px-1 py-px rounded bg-amber-100 dark:bg-amber-900/50 text-amber-700 dark:text-amber-300" title="定时任务会话:由任务触发写入">
                                    定时
                                  </span>
                                )}
                                <span className="truncate">{session.title}</span>
                              </div>
                              <div className={cn("text-[10px] tabular-nums pr-5", isSessionActive ? "text-blue-500/70 dark:text-blue-400/70" : "text-muted-foreground/60")}>
                                {session.messageCount != null && session.messageCount > 0 && <span>{session.messageCount} 条 · </span>}{relativeTime(session.updatedAt)}
                              </div>
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleDeleteSession(session.id, session.title);
                                }}
                                className="absolute right-1 top-1/2 -translate-y-1/2 p-1 rounded opacity-0 group-hover/session:opacity-100 hover:bg-red-50 dark:hover:bg-red-950/40 text-muted-foreground/50 hover:text-red-500 dark:hover:text-red-400 transition-all cursor-pointer"
                                title="删除会话"
                              >
                                <Trash2 className="w-3 h-3" />
                              </button>
                            </div>
                          );
                        })}
                        <div
                          onClick={() => {
                            createSession();
                            if (pathname !== "/chat") navigate("/chat");
                          }}
                          className="px-2 py-1.5 text-xs text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 rounded-md cursor-pointer transition-colors flex items-center gap-1.5 mt-1"
                        >
                          <Plus className="w-3.5 h-3.5" /> 新建对话
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </nav>
          </div>
        </div>
        <div className={cn("border-t border-border shrink-0", collapsed ? "p-2" : "p-3 sm:p-4")}>
          {/* 设置入口(数据源/环境已提升为并列主导航,2026-09-20 用户要求) */}
          <Link to="/settings" title={collapsed ? "设置" : undefined} className={cn("flex items-center rounded-lg text-sm font-medium transition-colors", collapsed ? "justify-center p-2.5" : "gap-3 px-3 py-2.5", pathname.startsWith("/settings") ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-muted-foreground hover:bg-muted hover:text-foreground")}><Settings className={cn("w-4 h-4 shrink-0", pathname.startsWith("/settings") ? "text-blue-600 dark:text-blue-400" : "text-muted-foreground")} />{!collapsed && "设置"}</Link>
        </div>
      </aside>
    </>
  );
}
