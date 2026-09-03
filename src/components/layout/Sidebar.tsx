'use client';

import Link from "next/link";
import Image from "next/image";
import { usePathname, useRouter } from "next/navigation";
import { 
  Home, Folder, MessageSquare, Zap, BookOpen, Server,
  Database, ListCheck, Settings, ChevronDown, ChevronUp, ChevronsUpDown,
  User, CreditCard, LogOut, Check, X, PanelLeftClose, PanelLeftOpen
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useState, useEffect } from "react";
import { useSidebarStore } from "@/hooks/useMobileMenu";
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
  { name: "文件", icon: Folder, href: "/files", subItems: [{ name: "全部文件", href: "/files" }, { name: "最近使用", href: "/files?view=recent" }, { name: "收藏文件", href: "/files?view=favorites" }, { name: "回收站", href: "/files?view=trash" }] },
  { name: "对话", icon: MessageSquare, href: "/chat", subItems: [{ name: "分析 Q2 销售数据", href: "/chat" }, { name: "产品需求文档优化", href: "/chat?session=2" }] },
  { name: "知识库", icon: BookOpen, href: "/knowledge" },
  { name: "AI 能力", icon: Zap, href: "/skills" },
  { name: "数据源", icon: Database, href: "/data-sources" },
  { name: "环境控制台", icon: Server, href: "/environments" },
  { name: "自动任务", icon: ListCheck, href: "/automations" },
  { name: "设置", icon: Settings, href: "/settings" },
];

export function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const { isOpen, setIsOpen, isCollapsed, toggleCollapsed } = useSidebarStore();
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    "文件": pathname.startsWith("/files"),
    "对话": pathname.startsWith("/chat"),
  });
  const toggleExpand = (name: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (isCollapsed) return;
    setExpanded(prev => ({ ...prev, [name]: !prev[name] }));
  };
  useEffect(() => { setIsOpen(false); }, [pathname, setIsOpen]);
  const collapsed = isCollapsed;
  return (
    <>
      {isOpen && <div className="fixed inset-0 bg-black/50 z-30 md:hidden animate-in fade-in" onClick={() => setIsOpen(false)} />}
      <aside className={cn("bg-white dark:bg-gray-900 border-r border-gray-200 dark:border-gray-800 flex flex-col justify-between flex-shrink-0 z-40 h-full transition-all duration-300 ease-in-out overflow-hidden", "fixed md:relative top-0 bottom-0 left-0", collapsed ? "w-[72px] md:w-[72px]" : "w-[260px]", isOpen ? "translate-x-0" : "-translate-x-full md:translate-x-0")}>
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className={cn("pt-4 pb-2 shrink-0 flex items-center gap-1", collapsed ? "flex-col gap-1.5 px-2" : "px-3 justify-between")}>
              {!collapsed ? (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                      <div className="p-2.5 flex-1 flex items-center justify-between hover:bg-gray-50 dark:hover:bg-gray-800 rounded-xl cursor-pointer transition-colors border border-transparent hover:border-gray-200 dark:hover:border-gray-800 group min-w-0">
                      <div className="flex items-center gap-3 min-w-0">
                          <Image src="/logo-mark.png" alt="AI 工作台" width={32} height={32} className="w-8 h-8 rounded-lg shrink-0" />
                          <div className="flex flex-col text-left min-w-0">
                          <span className="text-gray-800 dark:text-gray-100 font-bold text-sm truncate">Nora 的个人空间</span>
                          <span className="text-[10px] text-gray-500 dark:text-gray-400 truncate">专业版 · 个人</span>
                          </div>
                      </div>
                      <ChevronsUpDown className="w-3 h-3 text-gray-300 dark:text-gray-600 group-hover:text-gray-500 dark:group-hover:text-gray-400 shrink-0" />
                      </div>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent className="w-56 rounded-xl border-gray-200 dark:border-gray-800 shadow-lg" align="start">
                      <DropdownMenuLabel className="text-xs text-gray-500 dark:text-gray-400">工作区</DropdownMenuLabel>
                      <DropdownMenuItem className="flex items-center justify-between cursor-pointer rounded-lg"><div className="flex items-center gap-2"><Image src="/logo-mark.png" alt="" width={24} height={24} className="w-6 h-6 rounded" /><span className="text-sm font-medium">Nora 的个人空间</span></div><Check className="w-4 h-4 text-blue-600 dark:text-blue-400" /></DropdownMenuItem>
                      <DropdownMenuItem className="flex items-center gap-2 cursor-pointer rounded-lg opacity-70" onClick={() => toast.info('正在切换至 Team Alpha 工作区...')}><div className="w-6 h-6 bg-gray-200 dark:bg-gray-800 rounded flex items-center justify-center text-gray-600 dark:text-gray-300 text-[10px] font-bold">T</div><span className="text-sm">Team Alpha</span></DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="text-sm text-blue-600 dark:text-blue-400 cursor-pointer rounded-lg font-medium justify-center py-2" onClick={() => toast.success('正在创建新工作区...')}>创建新工作区</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              ) : (
                <Image src="/logo-mark.png" alt="AI 工作台" width={28} height={28} className="w-7 h-7 rounded-lg shrink-0" title="Nora 的个人空间" />
              )}
              <div className="flex items-center">
                <button className="hidden md:flex p-1.5 text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors shrink-0" onClick={toggleCollapsed} title={collapsed ? "展开导航" : "收起导航"}>
                  {collapsed ? <PanelLeftOpen className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
                </button>
                <button className="md:hidden ml-1 p-1.5 text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg shrink-0" onClick={() => setIsOpen(false)}><X className="w-5 h-5" /></button>
              </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scroll px-2 sm:px-3 py-2">
            <nav className="space-y-1">
              {NAV_ITEMS.map((item) => {
                const isActive = pathname === item.href || (item.href !== "/" && pathname.startsWith(item.href));
                const isExpanded = expanded[item.name];
                return (
                    <div key={item.name} className="mb-0.5">
                    <Link
                      href={item.href}
                      prefetch={true}
                      title={collapsed ? item.name : undefined}
                      onClick={(e) => { if (item.subItems) { e.preventDefault(); if (collapsed) { toast.info(item.name + " · 展开导航后可查看子菜单"); return; } toggleExpand(item.name, e); } }}
                      className={cn("flex items-center rounded-lg text-sm font-medium transition-all group cursor-pointer", collapsed ? "justify-center px-2 py-2.5" : "justify-between px-3 py-2.5", isActive && !item.subItems ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-50")}
                    >
                      <div className={cn("flex items-center", collapsed ? "justify-center" : "gap-3")}><item.icon className={cn("w-4 h-4 shrink-0", isActive && !item.subItems ? "text-blue-600 dark:text-blue-400" : "text-gray-400 dark:text-gray-500 group-hover:text-gray-600 dark:group-hover:text-gray-300")} />{!collapsed && <span className="truncate">{item.name}</span>}</div>
                      {!collapsed && item.subItems && (<div className="p-1 hover:bg-gray-200 dark:hover:bg-gray-700 rounded-md transition-colors shrink-0" onClick={(e) => toggleExpand(item.name, e)}>{isExpanded ? <ChevronUp className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" /> : <ChevronDown className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" />}</div>)}
                    </Link>
                    {!collapsed && item.subItems && isExpanded && (<div className="mt-1 ml-4 pl-3 border-l border-gray-100 dark:border-gray-800 space-y-0.5 animate-in slide-in-from-top-1 duration-200">{item.subItems.map((subItem) => (<Link key={subItem.name} href={subItem.href} className={cn("block px-3 py-2 rounded-lg text-xs transition-colors truncate", pathname === subItem.href ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 font-medium" : "text-gray-500 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-100 hover:bg-gray-50 dark:hover:bg-gray-800")}>{subItem.name}</Link>))}</div>)}
                  </div>
                );
              })}
            </nav>
          </div>
        </div>
        <div className={cn("border-t border-gray-200 dark:border-gray-800 shrink-0", collapsed ? "p-2" : "p-3 sm:p-4")}>
          <Link href="/settings" title={collapsed ? "设置中心" : undefined} className={cn("flex items-center rounded-lg text-sm font-medium transition-colors mb-2", collapsed ? "justify-center p-2.5" : "gap-3 px-3 py-2.5", pathname.startsWith("/settings") ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400" : "text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-50")}><Settings className={cn("w-4 h-4 shrink-0", pathname.startsWith("/settings") ? "text-blue-600 dark:text-blue-400" : "text-gray-400 dark:text-gray-500")} />{!collapsed && "设置中心"}</Link>
          {!collapsed ? (
            <DropdownMenu><DropdownMenuTrigger asChild><div className="flex items-center justify-between p-2 hover:bg-gray-50 dark:hover:bg-gray-800 rounded-xl cursor-pointer transition-colors border border-transparent hover:border-gray-200 dark:hover:border-gray-800 min-w-0"><div className="flex items-center gap-3 min-w-0"><div className="w-8 h-8 rounded-full shadow-sm shrink-0 bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-[10px] font-bold flex items-center justify-center">NC</div><div className="flex flex-col text-left min-w-0"><span className="text-gray-800 dark:text-gray-100 font-bold text-xs truncate">Nora Clark</span><span className="text-[10px] text-gray-500 dark:text-gray-400 truncate">超级管理员</span></div></div><ChevronDown className="w-3 h-3 text-gray-300 dark:text-gray-600 shrink-0" /></div></DropdownMenuTrigger><DropdownMenuContent className="w-56 mb-2 rounded-xl shadow-xl border-gray-200 dark:border-gray-800" align="start"><DropdownMenuLabel className="font-normal"><div className="flex flex-col space-y-1"><p className="text-sm font-medium leading-none">Nora Clark</p><p className="text-xs leading-none text-gray-500 dark:text-gray-400">nora.clark@example.com</p></div></DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem className="cursor-pointer py-2 rounded-lg" onClick={() => router.push('/settings')}><User className="mr-2 h-4 w-4 text-gray-500 dark:text-gray-400" /><span>个人资料</span></DropdownMenuItem><DropdownMenuItem className="cursor-pointer py-2 rounded-lg" onClick={() => router.push('/settings')}><CreditCard className="mr-2 h-4 w-4 text-gray-500 dark:text-gray-400" /><span>账单与订阅</span></DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuItem className="cursor-pointer text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40 focus:text-red-700 dark:focus:text-red-400 focus:bg-red-50 dark:focus:bg-red-950/40 py-2 rounded-lg" onClick={() => toast.success('已退出登录')}><LogOut className="mr-2 h-4 w-4" /><span>退出登录</span></DropdownMenuItem></DropdownMenuContent></DropdownMenu>
          ) : (<div className="flex justify-center" title="Nora Clark · 超级管理员"><div className="w-8 h-8 rounded-full shadow-sm bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-[10px] font-bold flex items-center justify-center">NC</div></div>)}
        </div>
      </aside>
    </>
  );
}
