'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, Eye, EyeOff, Copy, Check, KeyRound, Braces } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { MOCK_ENV_VARS, EnvVar } from "@/lib/devData";

export function EnvVarsSettings() {
  const [vars, setVars] = useState<EnvVar[]>(MOCK_ENV_VARS);
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
    setVars((prev) => [...prev, { key, value: newValue.trim(), secret: newSecret, note: newNote.trim() || undefined }]);
    setNewKey(""); setNewValue(""); setNewNote(""); setNewSecret(true);
    toast.success(`变量「${key}」已添加`);
  };

  const remove = (key: string) => {
    setVars((prev) => prev.filter((v) => v.key !== key));
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
      <div className="p-6 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="w-4 h-4 text-primary" />
          <label className="text-sm font-bold text-foreground">自定义凭据与环境变量</label>
        </div>
        <p className="text-xs text-muted-foreground">
          存放你自己的令牌与密钥（GitHub Token、模型 Key、Webhook 等），供自定义技能与自动任务引用。
        </p>

        {/* Add form */}
        <div className="rounded-lg border border-dashed border-border p-3 space-y-2">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <Input
              placeholder="变量名（如 GH_TOKEN）"
              className="h-9 text-sm font-mono uppercase"
              value={newKey}
              onChange={(e) => setNewKey(e.target.value.toUpperCase())}
            />
            <Input
              placeholder="值（Token / 密钥 / URL）"
              className="h-9 text-sm font-mono"
              value={newValue}
              onChange={(e) => setNewValue(e.target.value)}
            />
          </div>
          <div className="flex flex-col sm:flex-row gap-2 sm:items-center">
            <Input placeholder="用途备注（可选）" className="h-9 text-sm flex-1" value={newNote} onChange={(e) => setNewNote(e.target.value)} />
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-xs text-muted-foreground">设为密钥（脱敏）</span>
              <Switch checked={newSecret} onCheckedChange={setNewSecret} />
              <Button size="sm" className="h-9 px-3 text-xs" onClick={handleAdd}>
                <Plus className="w-3.5 h-3.5 mr-1" /> 添加
              </Button>
            </div>
          </div>
        </div>

        {/* List */}
        <div className="space-y-2">
          {vars.map((v) => (
            <div key={v.key} className="flex items-center gap-3 px-3 py-2.5 rounded-lg bg-muted/40 border border-border">
              <div className="w-40 shrink-0 min-w-0">
                <div className="text-xs font-mono font-bold text-foreground truncate">{v.key}</div>
                {v.note && <div className="text-[10px] text-muted-foreground truncate">{v.note}</div>}
              </div>
              <div className="flex-1 min-w-0 flex items-center gap-1.5">
                <code className="w-full truncate px-2 py-1 rounded bg-background border border-border text-[11px] font-mono text-muted-foreground">
                  {v.secret && !revealed[v.key] ? mask(v.value) : v.value}
                </code>
                {v.secret && (
                  <Button variant="ghost" size="icon" className="w-7 h-7 shrink-0 text-muted-foreground" title={revealed[v.key] ? "隐藏" : "显示"} onClick={() => setRevealed((p) => ({ ...p, [v.key]: !p[v.key] }))}>
                    {revealed[v.key] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </Button>
                )}
                <Button variant="ghost" size="icon" className="w-7 h-7 shrink-0 text-muted-foreground" title="复制" onClick={() => copy(v)}>
                  {copied === v.key ? <Check className="w-3.5 h-3.5 text-green-500" /> : <Copy className="w-3.5 h-3.5" />}
                </Button>
                <Button variant="ghost" size="icon" className="w-7 h-7 shrink-0 text-muted-foreground hover:text-destructive" title="删除" onClick={() => remove(v.key)}>
                  <Trash2 className="w-3.5 h-3.5" />
                </Button>
              </div>
            </div>
          ))}
          {vars.length === 0 && (
            <div className="py-8 text-center text-xs text-muted-foreground">还没有自定义变量，添加如 GH_TOKEN</div>
          )}
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
