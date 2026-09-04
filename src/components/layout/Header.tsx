'use client';

import { ChevronRight, Menu, Server } from "lucide-react";
import React from "react";
import { useRouter } from "next/navigation";
import { useSidebarStore } from "@/hooks/useSidebar";
import { NotificationBell } from "./NotificationBell";
import { MOCK_SERVICES } from "@/lib/devData";
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
  const toggleMenu = useSidebarStore((state) => state.toggle);
  const runningServices = MOCK_SERVICES.filter(s => s.status === "running");

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
        {/* Environment services indicator */}
        <button
          onClick={() => router.push("/environments")}
          className={`relative cursor-pointer flex items-center gap-1.5 px-2 py-1.5 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800 border border-transparent hover:border-gray-200 dark:hover:border-gray-800 transition-colors group shrink-0 ${runningServices.length === 0 ? "opacity-50 grayscale" : ""}`}
          title="环境控制台"
        >
          <Server className="w-4 h-4 text-green-500 dark:text-green-400 shrink-0" />
          <span className="text-xs font-medium text-gray-600 dark:text-gray-300 hidden lg:inline-block group-hover:text-green-600 dark:group-hover:text-green-400 transition-colors">
            {runningServices.length}/{MOCK_SERVICES.length} 服务运行中
          </span>
        </button>
        
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
