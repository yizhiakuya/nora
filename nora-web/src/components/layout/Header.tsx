'use client';

import { ChevronRight, Server } from "lucide-react";
import React from "react";
import { useNavigate } from "react-router-dom";
import { NavigationToggle } from "./NavigationToggle";
import { NotificationBell } from "./NotificationBell";
import { useServices } from "@/hooks/useServices";
import { WorkspaceControls } from "./WorkspaceControls";
import { Button } from "@/components/ui/button";
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
  compactActions?: boolean;
}

export function Header({ breadcrumbs, actions, compactActions = false }: HeaderProps) {
  const navigate = useNavigate();
  const services = useServices((s) => s.services);
  const unknownServices = services.filter((s) => s.health === "unknown");
  const runningServices = services.filter((s) => s.status === "running" && s.health !== "unknown");
  const serviceSummary = unknownServices.length
    ? `${runningServices.length} 已确认运行 · ${unknownServices.length} 未探测`
    : `${runningServices.length}/${services.length} 服务运行中`;

  return (
    <header className="app-toolbar h-auto min-h-12 flex-wrap justify-between gap-x-2 px-4 sm:px-6 z-10 sticky top-0 [container-type:inline-size]">
      <div className="order-1 flex min-h-12 flex-1 items-center gap-3 min-w-0">
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
      
      {actions && <div className={`order-2 flex min-w-0 items-center gap-2 ${compactActions ? "shrink-0" : "border-r border-border/60 pr-3 [@container(max-width:600px)]:order-3 [@container(max-width:600px)]:w-full [@container(max-width:600px)]:justify-end [@container(max-width:600px)]:border-r-0 [@container(max-width:600px)]:border-t [@container(max-width:600px)]:py-2 [@container(max-width:600px)]:pr-0"}`}>{actions}</div>}
      <div className="order-3 flex min-h-12 items-center gap-1 shrink-0 [@container(max-width:600px)]:order-2">
        {/* Environment services indicator */}
        <Button type="button" variant="ghost" size="icon"
          onClick={() => navigate("/environments")}
          className="h-8 w-8 shrink-0 text-muted-foreground"
          title={`环境控制台 · ${serviceSummary}`}
          aria-label={`环境控制台 · ${serviceSummary}`}
        >
          <Server className="w-4 h-4 text-muted-foreground shrink-0" />
        </Button>
        
        <WorkspaceControls />
        <NotificationBell />
      </div>
    </header>
  );
}
