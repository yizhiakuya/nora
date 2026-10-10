import { Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSidebarStore } from "@/hooks/useSidebar";

export function NavigationToggle() {
  const open = useSidebarStore(state => state.isOpen);
  const toggle = useSidebarStore(state => state.toggle);
  const setOpen = useSidebarStore(state => state.setIsOpen);

  return <Button variant="ghost" size="icon" className="md:hidden h-8 w-8 shrink-0 text-muted-foreground"
    aria-label="切换导航" title="导航与历史对话" aria-controls="workbench-navigation" aria-expanded={open}
    onClick={toggle} onKeyDown={event => { if (event.key === "Escape") setOpen(false); }}>
    <Menu />
  </Button>;
}
