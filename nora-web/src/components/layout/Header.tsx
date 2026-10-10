'use client';

import { ChevronRight, Server } from "lucide-react";
import React from "react";
import { useNavigate } from "react-router-dom";
import { NavigationToggle } from "./NavigationToggle";
import { NotificationBell } from "./NotificationBell";
import { useServices } from "@/hooks/useServices";
import { WorkspaceControls } from "./WorkspaceControls";
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
  const services = useServices((s) => s.services);
  const runningServices = services.filter((s) => s.status === "running");

  return (
    <header className="h-14 flex items-center justify-between gap-2 px-4 sm:px-6 border-b border-border/80 bg-card z-10 flex-shrink-0 sticky top-0 overflow-hidden [container-type:inline-size]">
      <div className="flex flex-1 items-center gap-3 min-w-0">
        <NavigationToggle />

        {/* Breadcrumbs */}
        <nav aria-label="面包屑" className="flex items-center gap-2 text-[13px] min-w-0">
          {breadcrumbs.map((bc, idx) => (
            <React.Fragment key={idx}>
              <button type="button" disabled={bc.isCurrent} aria-current={bc.isCurrent ? "page" : undefined}
                  className={bc.isCurrent ? "text-foreground font-medium min-w-0 truncate disabled:opacity-100" : "text-muted-foreground hover:text-foreground cursor-pointer transition-colors max-w-[100px] truncate rounded-sm [@container(max-width:600px)]:hidden"}
                  title={bc.label}
                  onClick={() => {
                    // 非当前段可点击:onClick 优先(页内状态回退),否则 href 导航
                    if (bc.isCurrent) return;
                    if (bc.onClick) bc.onClick();
                    else if (bc.href) navigate(bc.href);
                  }}
              >
                {bc.label}
              </button>
              {idx < breadcrumbs.length - 1 && (
                <ChevronRight className="w-3 h-3 text-muted-foreground/60 flex-shrink-0 [@container(max-width:600px)]:hidden" />
              )}
            </React.Fragment>
          ))}
        </nav>
      </div>
      
      {/* Right Actions & Global Task Indicator */}
      <div className="flex items-center gap-1.5 shrink-0 [@container(min-width:1000px)]:gap-3">
        {/* Environment services indicator */}
        <button
          onClick={() => navigate("/environments")}
          className={`relative cursor-pointer flex items-center gap-1.5 px-2 py-1.5 rounded-lg hover:bg-muted border border-transparent hover:border-border transition-colors group shrink-0 ${runningServices.length === 0 ? "opacity-50 grayscale" : ""}`}
          title="环境控制台"
        >
          <Server className="w-4 h-4 text-muted-foreground shrink-0" />
          <span className="text-xs text-muted-foreground hidden [@container(min-width:1000px)]:inline-block">
            {runningServices.length}/{services.length} 服务运行中
          </span>
        </button>
        
        {actions && (
          <>
            <div className="w-px h-4 bg-border hidden sm:block shrink-0"></div>
            {actions}
          </>
        )}
        
        <WorkspaceControls />
        <NotificationBell />
      </div>
    </header>
  );
}
