'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, Eye, EyeOff, Copy, Check, KeyRound, Braces } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { EnvVar } from "@/lib/devData";
import { usePreferences } from "@/hooks/usePreferences";

export function EnvVarsSettings() {
  const vars = usePreferences((s) => s.envVars);
  const addEnvVar = usePreferences((s) => s.addEnvVar);
  const removeEnvVar = usePreferences((s) => s.removeEnvVar);
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<string | null>(null);

  const [newKey, setNewKey] = useState("");
  const [newValue, setNewValue] = useState("");
  const [newNote, setNewNote] = useState("");
  const [newSecret, setNewSecret] = useState(true);

  const mask = (v: string) => (v.length <= 6 ? "••••" : `${v.slice(0, 3)}••••••${v.slice(-3)}`);

  const handleAdd = () => {
    const key = newKey.trim().toUpperCase();
    if (!/^[A-Z][A-Z0-9_]*$/.test(key)) {
      toast.error("变量名需为大写字母/数字/下划线，且以字母开头");
      return;
    }
    if (!newValue.trim()) {
      toast.error("请填写变量值");
      return;
    }
    if (vars.some((v) => v.key === key)) {
      toast.error(`「${key}」已存在，请直接编辑或先删除`);
      return;
    }
    addEnvVar({ key, value: newValue.trim(), secret: newSecret, note: newNote.trim() || undefined });
    setNewKey(""); setNewValue(""); setNewNote(""); setNewSecret(true);
    toast.success(`变量「${key}」已添加`);
  };

  const remove = (key: string) => {
    removeEnvVar(key);
    toast.success(`变量「${key}」已删除`);
  };

  const copy = async (v: EnvVar) => {
    try {
      await navigator.clipboard.writeText(v.secret && !revealed[v.key] ? mask(v.value) : v.value);
      setCopied(v.key);
      setTimeout(() => setCopied(null), 1200);
    } catch {
      toast.error("复制失败，请手动选择");
    }
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div>
          <div className="flex items-center gap-2 mb-1.5">
            <KeyRound className="w-4 h-4 text-primary" />
            <label className="text-sm font-bold text-foreground">自定义凭据与环境变量</label>
          </div>
          <p className="text-xs text-muted-foreground">
            存放你自己的令牌与密钥（GitHub Token、模型 Key、Webhook 等），供自定义技能与自动任务引用。
          </p>
        </div>

        {/* Add form */}
        <div className="bg-muted/20 border border-border rounded-lg p-5 space-y-4">
          <div className="text-sm font-medium text-foreground">添加新变量</div>
          <div className="grid grid-cols-1 md:grid-cols-12 gap-4">
            <div className="md:col-span-4">
              <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">变量名</label>
              <Input
                placeholder="例如：GH_TOKEN"
                className="h-9 text-sm font-mono uppercase bg-background"
                value={newKey}
                onChange={(e) => setNewKey(e.target.value.toUpperCase())}
              />
            </div>
            <div className="md:col-span-8">
              <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">值</label>
              <Input
                placeholder="Token / 密钥 / URL"
                className="h-9 text-sm font-mono bg-background"
                value={newValue}
                onChange={(e) => setNewValue(e.target.value)}
              />
            </div>
          </div>
          <div className="flex flex-col sm:flex-row gap-4 sm:items-center justify-between pt-2">
            <div className="flex-1 max-w-sm">
              <Input placeholder="用途备注（可选）" className="h-9 text-sm bg-background" value={newNote} onChange={(e) => setNewNote(e.target.value)} />
            </div>
            <div className="flex items-center gap-4 shrink-0">
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground">作为密钥脱敏</span>
                <Switch checked={newSecret} onCheckedChange={setNewSecret} />
              </div>
              <Button size="sm" className="h-9 px-4 text-xs bg-primary text-primary-foreground hover:bg-primary/90" onClick={handleAdd}>
                <Plus className="w-3.5 h-3.5 mr-1" /> 添加
              </Button>
            </div>
          </div>
        </div>

        {/* List */}
        <div>
          <div className="text-sm font-medium text-foreground mb-3">已保存的变量 ({vars.length})</div>
          <div className="border border-border rounded-lg overflow-hidden">
            {vars.length === 0 ? (
              <div className="py-10 text-center text-xs text-muted-foreground bg-background/30">还没有自定义变量</div>
            ) : (
              <div className="divide-y divide-border">
                {vars.map((v) => (
                  <div key={v.key} className="flex flex-col sm:flex-row sm:items-center gap-3 px-4 py-3 bg-background/30 hover:bg-muted/30 transition-colors">
                    <div className="w-48 shrink-0 min-w-0">
                      <div className="text-xs font-mono font-bold text-foreground truncate">{v.key}</div>
                      {v.note && <div className="text-[10px] text-muted-foreground truncate mt-0.5">{v.note}</div>}
                    </div>
                    <div className="flex-1 min-w-0 flex items-center gap-2">
                      <div className="flex-1 px-3 py-1.5 rounded-md bg-muted/50 border border-border text-xs font-mono text-muted-foreground truncate">
                        {v.secret && !revealed[v.key] ? mask(v.value) : v.value}
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        {v.secret && (
                          <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-foreground" title={revealed[v.key] ? "隐藏" : "显示"} onClick={() => setRevealed((p) => ({ ...p, [v.key]: !p[v.key] }))}>
                            {revealed[v.key] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                          </Button>
                        )}
                        <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-foreground" title="复制" onClick={() => copy(v)}>
                          {copied === v.key ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                        </Button>
                        <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-destructive" title="删除" onClick={() => remove(v.key)}>
                          <Trash2 className="w-4 h-4" />
                        </Button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="bg-muted/30 px-6 py-3 border-t border-border flex items-start gap-2">
        <Braces className="w-3.5 h-3.5 text-muted-foreground mt-0.5 shrink-0" />
        <p className="text-[11px] text-muted-foreground leading-relaxed">
          引用方式：在自定义技能与自动任务配置中写 <code className="font-mono text-foreground">{"{{GH_TOKEN}}"}</code>，
          执行时自动替换为真实值；标记为密钥的变量对 AI 脱敏，不会出现在对话上下文中。
        </p>
      </div>
    </div>
  );
}