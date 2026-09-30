'use client';

import { ChevronRight, Menu, Server } from "lucide-react";
import React from "react";
import { useNavigate } from "react-router-dom";
import { useSidebarStore } from "@/hooks/useSidebar";
import { NotificationBell } from "./NotificationBell";
import { useServices } from "@/hooks/useServices";
interface Breadcrumb {
  label: string;
  href?: string;
  /** 自定义点击行为(优先于 href;用于页内状态回退,如退出文件夹视图)。 */
  onClick?: () => void;
  isCurrent?: boolean;
}

interface HeaderProps {
  breadcrumbs: Breadcrumb[];
  actions?: React.ReactNode;
}

export function Header({ breadcrumbs, actions }: HeaderProps) {
  const navigate = useNavigate();
  const toggleMenu = useSidebarStore((state) => state.toggle);
  const services = useServices((s) => s.services);
  const runningServices = services.filter((s) => s.status === "running");

  return (
    <header className="h-14 flex items-center justify-between gap-3 px-4 border-b border-border bg-card z-10 flex-shrink-0 shadow-sm sticky top-0 overflow-hidden [container-type:inline-size]">
      <div className="flex flex-1 items-center gap-3 min-w-0">
        {/* Mobile Menu Toggle */}
        <button
          onClick={toggleMenu}
          aria-label="切换导航"
          className="md:hidden p-1.5 -ml-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-md transition-colors shrink-0"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Breadcrumbs */}
        <div className="flex items-center gap-2 text-sm font-medium min-w-0">
          {breadcrumbs.map((bc, idx) => (
            <React.Fragment key={idx}>
              <span
                  className={bc.isCurrent ? "text-foreground min-w-0 truncate" : "text-muted-foreground hover:text-foreground cursor-pointer transition-colors max-w-[80px] truncate [@container(max-width:600px)]:hidden"}
                  title={bc.label}
                  onClick={() => {
                    // 非当前段可点击:onClick 优先(页内状态回退),否则 href 导航
                    if (bc.isCurrent) return;
                    if (bc.onClick) bc.onClick();
                    else if (bc.href) navigate(bc.href);
                  }}
              >
                {bc.label}
              </span>
              {idx < breadcrumbs.length - 1 && (
                <ChevronRight className="w-3 h-3 text-muted-foreground/60 flex-shrink-0 [@container(max-width:600px)]:hidden" />
              )}
            </React.Fragment>
          ))}
        </div>
      </div>
      
      {/* Right Actions & Global Task Indicator */}
      <div className="flex items-center gap-2 shrink-0 [@container(min-width:1000px)]:gap-4">
        {/* Environment services indicator */}
        <button
          onClick={() => navigate("/environments")}
          className={`relative cursor-pointer flex items-center gap-1.5 px-2 py-1.5 rounded-lg hover:bg-muted border border-transparent hover:border-border transition-colors group shrink-0 ${runningServices.length === 0 ? "opacity-50 grayscale" : ""}`}
          title="环境控制台"
        >
          <Server className="w-4 h-4 text-green-500 dark:text-green-400 shrink-0" />
          <span className="text-xs font-medium text-muted-foreground hidden [@container(min-width:1000px)]:inline-block group-hover:text-green-600 dark:group-hover:text-green-400 transition-colors">
            {runningServices.length}/{services.length} 服务运行中
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
