import { useEffect, useRef, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { cn } from "@/lib/utils";

interface ConfigurationLayoutProps {
  title: string;
  description: string;
  sections: readonly { key: string; label: string; href: string }[];
  active: string;
  children: ReactNode;
}

export function ConfigurationLayout({ title, description, sections, active, children }: ConfigurationLayoutProps) {
  const scrollArea = useRef<HTMLDivElement>(null);
  useEffect(() => { scrollArea.current?.scrollTo({ top: 0 }); }, [active]);
  return <>
    <Header breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: title, isCurrent: true }]} />
    <div ref={scrollArea} className="page-content no-scrollbar">
      <div className="page-container [container-type:inline-size]">
        <div>
          <h1 className="page-heading">{title}</h1>
          <p className="page-description">{description}</p>
        </div>
        <div className="grid min-w-0 gap-5 [@container(min-width:900px)]:grid-cols-[168px_minmax(0,1fr)] [@container(min-width:900px)]:gap-8">
          <nav aria-label={`${title} 分类`} className="flex gap-1 overflow-x-auto pb-2 no-scrollbar [@container(min-width:900px)]:flex-col [@container(min-width:900px)]:self-start [@container(min-width:900px)]:sticky [@container(min-width:900px)]:top-0 [@container(min-width:900px)]:pb-0">
            {sections.map(section => <Link key={section.key} to={section.href} replace
              aria-current={active === section.key ? "page" : undefined}
              className={cn("shrink-0 rounded-lg border border-transparent px-3 py-2 text-left text-[13px] transition-colors",
                active === section.key ? "bg-card border-border/70 font-medium text-foreground shadow-surface" : "text-muted-foreground hover:bg-muted hover:text-foreground")}
            >{section.label}</Link>)}
          </nav>
          <div className="min-w-0 space-y-6">{children}</div>
        </div>
      </div>
    </div>
  </>;
}
