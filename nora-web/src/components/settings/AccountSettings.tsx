'use client';

import { useState } from "react";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { usePreferences } from "@/hooks/usePreferences";
import { toast } from "sonner";

export function AccountSettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const account = usePreferences((s) => s.account);
  const setAccount = usePreferences((s) => s.setAccount);

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("账号信息已保存");
    schedule(() => setSaved(false), 2000);
  };

  const initials = account.name
    .split(/\s+/)
    .map((part) => part[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-lg font-bold flex items-center justify-center shadow-sm shrink-0">{initials}</div>
          <div>
            <div className="text-sm font-bold text-foreground">{account.name}</div>
            <div className="text-xs text-muted-foreground mt-0.5">{account.email} · 自部署</div>
            <button type="button" className="text-xs text-primary hover:underline mt-1 cursor-pointer" onClick={() => toast.info("头像上传为演示功能")}>更换头像</button>
          </div>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">昵称</label>
            <Input value={account.name} onChange={(e) => setAccount({ name: e.target.value })} className="h-10" />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">邮箱</label>
            <Input value={account.email} onChange={(e) => setAccount({ email: e.target.value })} className="h-10" />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">时区</label>
            <Select value={account.timezone} onValueChange={(v) => setAccount({ timezone: v })}>
              <SelectTrigger className="h-10"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="Asia/Shanghai (UTC+8)">Asia/Shanghai (UTC+8)</SelectItem>
                <SelectItem value="UTC">UTC</SelectItem>
                <SelectItem value="America/New_York">America/New_York</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">界面语言</label>
            <Select value={account.lang} onValueChange={(v) => setAccount({ lang: v })}>
              <SelectTrigger className="h-10"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="简体中文">简体中文</SelectItem>
                <SelectItem value="English">English</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end">
        <Button size="sm" className={`transition-colors ${saved ? "bg-green-600 dark:bg-green-500 text-white" : "bg-primary text-primary-foreground"}`} onClick={handleSave}>
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
