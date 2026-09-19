'use client';

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, Copy, Check, KeyRound, Braces } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { requestJson, USE_BACKEND } from "@/lib/api/client";

/**
 * 自定义环境变量(2026-09-19 真实落地):存 agent-service app_setting 表
 * (跨浏览器一致),run_command 执行时注入子进程环境——「设置后 AI 能用到」
 * 从空头承诺变为真实行为。secret 值读取时打码(服务端只回前 3+后 3)。
 *
 * Mock 模式(USE_BACKEND=false):纯本地状态,仅 UI 演示。
 */
interface EnvVar {
  key: string;
  value: string;
  secret: boolean;
  note?: string;
}

export function EnvVarsSettings() {
  const [vars, setVars] = useState<EnvVar[]>([]);
  const [loading, setLoading] = useState(USE_BACKEND);
  const [copied, setCopied] = useState<string | null>(null);

  const [newKey, setNewKey] = useState("");
  const [newValue, setNewValue] = useState("");
  const [newNote, setNewNote] = useState("");
  const [newSecret, setNewSecret] = useState(true);

  const load = useCallback(async () => {
    if (!USE_BACKEND) {
      setLoading(false);
      return;
    }
    try {
      const rows = await requestJson<EnvVar[]>("/chat/settings/env-vars");
      setVars(rows);
    } catch {
      toast.error("读取环境变量失败(后端不可用?)");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 全量保存到服务端(前端是唯一编辑器,全量语义最直白)。 */
  const persist = useCallback(async (next: EnvVar[]) => {
    const prev = vars;
    setVars(next);
    if (!USE_BACKEND) return;
    try {
      const rows = await requestJson<EnvVar[]>("/chat/settings/env-vars", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ vars: next }),
      });
      setVars(rows); // 服务端回读(secret 已打码)
    } catch (e) {
      setVars(prev); // 失败回滚
      toast.error(`保存失败:${e instanceof Error ? e.message : String(e)}`);
      throw e;
    }
  }, [vars]);

  const handleAdd = async () => {
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
      toast.error(`「${key}」已存在，请先删除再添加`);
      return;
    }
    try {
      await persist([...vars, { key, value: newValue.trim(), secret: newSecret, note: newNote.trim() || undefined }]);
      setNewKey(""); setNewValue(""); setNewNote(""); setNewSecret(true);
      toast.success(`变量「${key}」已保存,AI 执行命令时可用`);
    } catch {
      /* persist 已 toast */
    }
  };

  const remove = async (key: string) => {
    try {
      await persist(vars.filter((v) => v.key !== key));
      toast.success(`变量「${key}」已删除`);
    } catch {
      /* persist 已 toast */
    }
  };

  const copy = async (v: EnvVar) => {
    try {
      // 服务端读取时 secret 已打码——复制到的是打码值(真值仅在服务端,
      // 用于命令注入;想核对真值可在添加时确认)
      await navigator.clipboard.writeText(v.value);
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
            存放你自己的令牌与密钥（GitHub Token、模型 Key、Webhook 等）。保存到服务端(跨浏览器一致),
            <span className="text-foreground font-medium">AI 通过 run_command 执行命令时自动注入为环境变量</span>
            ——例如存了 <code className="font-mono">GH_TOKEN</code>,命令里可直接用 <code className="font-mono">$env:GH_TOKEN</code> 引用。
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
          <div className="text-sm font-medium text-foreground mb-3">
            已保存的变量 ({vars.length}){loading && <span className="text-xs text-muted-foreground ml-2">加载中…</span>}
          </div>
          <div className="border border-border rounded-lg overflow-hidden">
            {vars.length === 0 && !loading ? (
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
                        {v.value}
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
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
          变量存于服务端(app_setting 表),AI 执行 <code className="font-mono text-foreground">run_command</code> 时
          自动注入为子进程环境变量(PowerShell 用 <code className="font-mono text-foreground">$env:KEY</code>、
          bash 用 <code className="font-mono text-foreground">$KEY</code> 引用);
          标记为密钥的变量读取时打码,不会出现在对话上下文中。
        </p>
      </div>
    </div>
  );
}
