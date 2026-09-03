'use client';

import { ChevronRight, Loader2, Menu } from "lucide-react";
import React from "react";
import { useRouter } from "next/navigation";
import { useMobileMenu } from "@/hooks/useMobileMenu";
import { useGlobalTasks } from "@/hooks/useGlobalTasks";
import { NotificationBell } from "./NotificationBell";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

interface Breadcrumb {
  label: string;
  href?: string;
  isCurrent?: boolean;
}

interface HeaderProps {
  breadcrumbs: Breadcrumb[];
  actions?: React.ReactNode;
}

export function Header({ breadcrumbs, actions }: HeaderProps) {
  const router = useRouter();
  const toggleMenu = useMobileMenu((state) => state.toggle);
  const tasks = useGlobalTasks((state) => state.tasks);
  const runningTasks = tasks.filter(t => t.status === 'running');

  return (
    <header className="h-14 flex items-center justify-between px-4 sm:px-6 border-b border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 z-10 flex-shrink-0 shadow-sm sticky top-0 overflow-hidden">
      <div className="flex items-center gap-3 shrink-0 min-w-0">
        {/* Mobile Menu Toggle */}
        <button 
          onClick={toggleMenu}
          className="md:hidden p-1.5 -ml-1.5 text-gray-500 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-100 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-md transition-colors shrink-0"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Breadcrumbs */}
        <div className="hidden sm:flex items-center gap-2 text-sm font-medium min-w-0">
          {breadcrumbs.map((bc, idx) => (
            <React.Fragment key={idx}>
              <span 
                  className={bc.isCurrent ? "text-gray-900 dark:text-gray-50 flex items-center gap-2 max-w-[100px] lg:max-w-none truncate" : "text-gray-500 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-100 cursor-pointer transition-colors max-w-[80px] lg:max-w-none truncate"}
                  onClick={() => { if (bc.href) router.push(bc.href); }}
              >
                {bc.label}
              </span>
              {idx < breadcrumbs.length - 1 && (
                <ChevronRight className="w-3 h-3 text-gray-300 dark:text-gray-600 flex-shrink-0" />
              )}
            </React.Fragment>
          ))}
        </div>
      </div>
      
      {/* Right Actions & Global Task Indicator */}
      <div className="flex items-center gap-2 sm:gap-4 shrink-0">
        {/* Active Tasks Dropdown */}
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <div className={`relative cursor-pointer flex items-center gap-1.5 px-2 py-1.5 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800 border border-transparent hover:border-gray-200 dark:hover:border-gray-800 transition-colors group shrink-0 ${runningTasks.length === 0 ? 'opacity-50 grayscale' : ''}`}>
                    <Loader2 className={`w-4 h-4 text-blue-500 dark:text-blue-400 shrink-0 ${runningTasks.length > 0 ? 'animate-spin' : ''}`} />
                    <span className="text-xs font-medium text-gray-600 dark:text-gray-300 hidden lg:inline-block group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">
                      {runningTasks.length} 任务运行中
                    </span>
                </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-72 sm:w-80 p-0 border-gray-200 dark:border-gray-800 rounded-xl shadow-lg">
                <div className="bg-gray-50 dark:bg-gray-900 px-4 py-3 border-b border-gray-100 dark:border-gray-800 flex items-center justify-between rounded-t-xl">
                    <span className="text-xs font-bold text-gray-700 dark:text-gray-200">后台运行任务</span>
                    <span className="text-[10px] text-blue-600 dark:text-blue-400 hover:underline cursor-pointer" onClick={() => router.push('/tasks')}>查看全部</span>
                </div>
                <div className="p-2 space-y-1 max-h-[300px] overflow-y-auto custom-scroll">
                    {runningTasks.length === 0 ? (
                        <div className="p-4 text-center text-xs text-gray-500 dark:text-gray-400">当前没有运行中的任务</div>
                    ) : runningTasks.map(task => (
                        <div key={task.id} className="p-3 bg-white dark:bg-gray-900 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer border border-transparent hover:border-gray-200 dark:hover:border-gray-800 transition-colors flex gap-3">
                            <Loader2 className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin flex-shrink-0 mt-0.5" />
                            <div className="flex-1 min-w-0">
                                <div className="text-xs font-bold text-gray-800 dark:text-gray-100 mb-1 truncate" title={task.name}>{task.name}</div>
                                <div className="text-[10px] text-gray-500 dark:text-gray-400 flex items-center gap-2">
                                    <span>{task.agent}</span>
                                    <span>·</span>
                                    <span className="text-blue-600 dark:text-blue-400 font-mono">执行中</span>
                                </div>
                                <div className="w-full h-1 bg-gray-100 dark:bg-gray-800 rounded-full overflow-hidden flex mt-2">
                                    <div className="h-full bg-blue-500 rounded-full w-2/3 animate-pulse"></div>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </DropdownMenuContent>
        </DropdownMenu>
        
        {actions && (
          <>
            <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 hidden sm:block shrink-0"></div>
            {actions}
          </>
        )}
        
        <NotificationBell />
      </div>
    </header>
  );
}
