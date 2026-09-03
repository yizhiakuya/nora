import { User, Bell, Key, CreditCard, Monitor, LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";

const SETTINGS_TABS = [
  { name: "通用", icon: Monitor },
  { name: "账号", icon: User },
  { name: "安全与 API", icon: Key },
  { name: "通知", icon: Bell },
  { name: "订阅", icon: CreditCard },
];

export function SettingsNav({ activeTab, onSelect }: { activeTab: string; onSelect: (name: string) => void }) {
  return (
    <div className="w-56 bg-card dark:bg-card border-r border-border flex flex-col pt-6 z-10 shrink-0 hidden md:flex">
      <h2 className="px-6 text-xs font-bold text-muted-foreground uppercase tracking-wider mb-4">系统设置</h2>
      <nav className="flex-1 px-3 space-y-1">
        {SETTINGS_TABS.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.name;
          return (
            <div
              key={tab.name}
              onClick={() => onSelect(tab.name)}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors ${isActive ? "bg-primary/10 text-primary" : "text-muted-foreground hover:bg-muted/50 hover:text-foreground"}`}
            >
              <Icon className={`w-4 h-4 ${isActive ? "text-primary" : "text-muted-foreground"}`} />
              {tab.name}
            </div>
          );
        })}
      </nav>
      <div className="p-4 border-t border-border">
        <Button variant="ghost" className="w-full justify-start text-destructive hover:text-destructive hover:bg-destructive/10 text-sm h-9">
          <LogOut className="w-4 h-4 mr-2" /> 退出登录
        </Button>
      </div>
    </div>
  );
}
