'use client';

import { useState } from "react";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { toast } from "sonner";

export function AccountSettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const [name, setName] = useState("Nora Clark");
  const [email, setEmail] = useState("nora.clark@example.com");
  const [timezone, setTimezone] = useState("Asia/Shanghai (UTC+8)");
  const [lang, setLang] = useState("简体中文");

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("账号信息已保存");
    schedule(() => setSaved(false), 2000);
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 text-white text-lg font-bold flex items-center justify-center shadow-sm shrink-0">NC</div>
          <div>
            <div className="text-sm font-bold text-foreground">{name}</div>
            <div className="text-xs text-muted-foreground mt-0.5">{email} · 自部署</div>
            <button type="button" className="text-xs text-primary hover:underline mt-1 cursor-pointer" onClick={() => toast.info("头像上传为演示功能")}>更换头像</button>
          </div>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">昵称</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} className="h-10" />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">邮箱</label>
            <Input value={email} onChange={(e) => setEmail(e.target.value)} className="h-10" />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">时区</label>
            <Select value={timezone} onValueChange={setTimezone}>
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
            <Select value={lang} onValueChange={setLang}>
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
