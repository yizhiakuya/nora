'use client';

import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  Home, Folder, MessageSquare, Zap, BookOpen, Server,
  Database, ListCheck, Settings, ChevronDown, ChevronsUpDown,
  User, LogOut, Check, X, PanelLeftClose, PanelLeftOpen, Plus
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useEffect } from "react";
import { useSidebarStore } from "@/hooks/useSidebar";
import { usePreferences } from "@/hooks/usePreferences";
import { useChatSessions } from "@/hooks/useChatSessions";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "sonner";

const NAV_ITEMS = [
{ name: "首页", icon: Home, href: "/" },
{ name: "文件", icon: Folder, href: "/files" },
{ name: "对话", icon: MessageSquare, href: "/chat" },
{ name: "知识库", icon: BookOpen, href: "/knowledge" },
{ name: "AI 能力", icon: Zap, href: "/skills" },
{ name: "数据源", icon: Database, href: "/data-sources" },
{ name: "环境控制台", icon: Server, href: "/environments" },
{ name: "自动任务", icon: ListCheck, href: "/automations" },
{ name: "设置", icon: Settings, href: "/settings" },
];

export function Sidebar() {
  const location = useLocation();
  const pathname = location.pathname;
  const navigate = useNavigate();

  const { isOpen, setIsOpen, isCollapsed, toggleCollapsed } = useSidebarStore();
  const accountName = usePreferences((s) => s.account.name);
  const accountEmail = usePreferences((s) => s.account.email);

  // Chat sessions
  const sessions = useChatSessions((s) => s.sessions);
  const activeId = useChatSessions((s) => s.activeId);
  const setActive = useChatSessions((s) => s.setActive);
  const createSession = useChatSessions((s) => s.createSession);

  const initials = accountName
    .split(/\s+/)
    .map((part) => part[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();

  useEffect(() => { setIsOpen(false); }, [pathname, setIsOpen]);

  const collapsed = isCollapsed;
  const recentSessions = sessions.slice(0, 8);

  return (
    <>
      {isOpen && <div className="fixed inset-0 bg-black/50 z-30 md:hidden animate-in fade-in" onClick={() => setIsOpen(false)} />}
      <aside className={cn("bg-card border-r border-border flex flex-col justify-between flex-shrink-0 z-40 h-full transition-all duration-300 ease-in-out overflow-hidden", "fixed md:relative top-0 bottom-0 left-0", collapsed ? "w-[72px] md:w-[72px]" : "w-[260px]", isOpen ? "translate-x-0" : "-translate-x-full md:translate-x-0")}>
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className={cn("pt-4 pb-2 shrink-0 flex items-center gap-1", collapsed ? "flex-col gap-1.5 px-2" : "px-3 justify-between")}>
              {!collapsed ? (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                      <div className="p-2.5 flex-1 flex items-center justify-between hover:bg-muted rounded-xl cursor-pointer transition-colors border border-transparent hover:border-border group min-w-0">
                      <div className="flex items-center gap-3 min-w-0">
                          <img src="/logo-mark.png" alt="AI 工作台" width={32} height={32} className="w-8 h-8 rounded-lg shrink-0" />
                          <div className="flex flex-col text-left min-w-0">
                          <span className="text-foreground font-bold text-sm truncate">Nora 的个人空间</span>
                          <span className="text-[10px] text-muted-foreground truncate">自部署版</span>
                          </div>
                      </div>
                      <ChevronsUpDown className="w-3 h-3 text-muted-foreground/60 group-hover:text-foreground shrink-0" />
                      </div>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent className="w-56 rounded-xl border-border shadow-lg" align="start">
                      <DropdownMenuLabel className="text-xs text-muted-foreground">工作区</DropdownMenuLabel>
                      <DropdownMenuItem className="flex items-center justify-between cursor-pointer rounded-lg"><div className="flex items-center gap-2"><img src="/logo-mark.png" alt="" width={24} height={24} className="w-6 h-6 rounded" /><span className="text-sm font-medium">Nora 的个人空间</span></div><Check className="w-4 h-4 text-blue-600 dark:text-blue-400" /></DropdownMenuItem>
                      <DropdownMenuItem className="flex items-center gap-2 cursor-pointer rounded-lg opacity-70" onClick={() => toast.info('正在切换至 Team Alpha 工作区...')}><div className="w-6 h-6 bg-gray-200 dark:bg-gray-800 rounded flex items-center justify-center text-muted-foreground text-[10px] font-bold">T</div><span className="text-sm">Team Alpha</span></DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="text-sm text-blue-600 dark:text-blue-400 cursor-pointer rounded-lg font-medium justify-center py-2" onClick={() => toast.success('正在创建新工作区...')}>创建新工作区</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              ) : (
                <img src="/logo-mark.png" alt="AI 工作台" width={28} height={28} className="w-7 h-7 rounded-lg shrink-0" title="Nora 的个人空间" />
              )}
              <div className="flex items-center">
                <button className="hidden md:flex p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-lg transition-colors shrink-0" onClick={toggleCollapsed} title={collapsed ? "展开导航" : "收起导航"}>
                  {collapsed ? <PanelLeftOpen className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
                </button>
                <button className="md:hidden ml-1 p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-lg shrink-0" onClick={() => setIsOpen(false)}><X className="w-5 h-5" /></button>
              </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scroll px-2 sm:px-3 py-2">
            <nav className="space-y-1">
              {NAV_ITEMS.map((item) => {
                const isActive = pathname === item.href || (item.href !== "/" && pathname.startsWith(item.href));
                return (
                  <div key={item.name} className="mb-0.5 flex flex-col">
                    <Link
                      to={item.href}
                      title={collapsed ? item.name : undefined}
                      className={cn("flex items-center rounded-lg text-sm font-medium transition-all group cursor-pointer", collapsed ? "justify-center px-2 py-2.5" : "px-3 py-2.5", isActive ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-muted-foreground hover:bg-muted hover:text-foreground")}
                    >
                      <div className={cn("flex items-center", collapsed ? "justify-center" : "gap-3")}><item.icon className={cn("w-4 h-4 shrink-0", isActive ? "text-blue-600 dark:text-blue-400" : "text-muted-foreground group-hover:text-foreground")} />{!collapsed && <span className="truncate">{item.name}</span>}</div>
                    </Link>

                    {/* Chat Sessions Secondary Menu */}
                    {item.name === "对话" && !collapsed && (
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
                                "px-2 py-1.5 text-xs rounded-md cursor-pointer truncate transition-colors",
                                isSessionActive
                                  ? "bg-blue-50/50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-400 font-medium"
                                  : "text-muted-foreground hover:bg-muted hover:text-gray-900 dark:hover:text-gray-100"
                              )}
                              title={session.title}
                            >
                              {session.title}
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
          <Link to="/settings" title={collapsed ? "设置中心" : undefined} className={cn("flex items-center rounded-lg text-sm font-medium transition-colors mb-2", collapsed ? "justify-center p-2.5" : "gap-3 px-3 py-2.5", pathname.startsWith("/settings") ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-muted-foreground hover:bg-muted hover:text-foreground")}><Settings className={cn("w-4 h-4 shrink-0", pathname.startsWith("/settings") ? "text-blue-600 dark:text-blue-400" : "text-muted-foreground")} />{!collapsed && "设置中心"}</Link>
          {!collapsed ? (
            <DropdownMenu><DropdownMenuTrigger asChild><div className="flex items-center justify-between p-2 hover:bg-muted rounded-xl cursor-pointer transition-colors border border-transparent hover:border-border min-w-0"><div className="flex items-center gap-3 min-w-0"><div className="w-8 h-8 rounded-full shadow-sm shrink-0 bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-[10px] font-bold flex items-center justify-center">{initials}</div><div className="flex flex-col text-left min-w-0"><span className="text-foreground font-bold text-xs truncate">{accountName}</span><span className="text-[10px] text-muted-foreground truncate">开发者</span></div></div><ChevronDown className="w-3 h-3 text-muted-foreground/60 shrink-0" /></div></DropdownMenuTrigger><DropdownMenuContent className="w-56 mb-2 rounded-xl shadow-xl border-border" align="start"><DropdownMenuLabel className="font-normal"><div className="flex flex-col space-y-1"><p className="text-sm font-medium leading-none">{accountName}</p><p className="text-xs leading-none text-muted-foreground">{accountEmail}</p></div></DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem className="cursor-pointer py-2 rounded-lg" onClick={() => navigate('/settings')}><User className="mr-2 h-4 w-4 text-muted-foreground" /><span>个人资料</span></DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuItem className="cursor-pointer text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40 focus:text-red-700 dark:focus:text-red-400 focus:bg-red-50 dark:focus:bg-red-950/40 py-2 rounded-lg" onClick={() => toast.success('已退出登录')}><LogOut className="mr-2 h-4 w-4" /><span>退出登录</span></DropdownMenuItem></DropdownMenuContent></DropdownMenu>
          ) : (<div className="flex justify-center" title={`${accountName} · 开发者`}><div className="w-8 h-8 rounded-full shadow-sm bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-[10px] font-bold flex items-center justify-center">{initials}</div></div>)}
        </div>
      </aside>
    </>
  );
}
