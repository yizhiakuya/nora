'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, Eye, EyeOff, CheckCircle2, XCircle, Loader2, Zap, Globe } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useModelProviders } from "@/hooks/useModelProviders";

const PROVIDER_URLS: { match: RegExp; url: string; models: string[] }[] = [
  { match: /openai/i,     url: "https://api.openai.com/v1",                             models: ["GPT-4o", "GPT-4o mini", "o3-mini"] },
  { match: /anthropic/i,  url: "https://api.anthropic.com/v1",                          models: ["Claude 3.5 Sonnet", "Claude 3.5 Haiku"] },
  { match: /gemini|google/i, url: "https://generativelanguage.googleapis.com/v1beta",   models: ["Gemini 1.5 Pro", "Gemini 1.5 Flash"] },
  { match: /deepseek/i,   url: "https://api.deepseek.com/v1",                           models: ["deepseek-chat", "deepseek-reasoner"] },
  { match: /ollama|本地/i, url: "http://localhost:11434/v1",                             models: ["qwen2.5:7b", "llama3.1:8b"] },
];

export function ModelSettings() {
  const providers = useModelProviders((s) => s.providers);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const setDefaultModel = useModelProviders((s) => s.setDefaultModel);
  const addProvider = useModelProviders((s) => s.addProvider);
  const removeProvider = useModelProviders((s) => s.removeProvider);
  const toggleEnabled = useModelProviders((s) => s.toggleEnabled);
  const markStatus = useModelProviders((s) => s.markStatus);

  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [key, setKey] = useState("");
  const [revealed, setRevealed] = useState<Record<number, boolean>>({});
  const [testing, setTesting] = useState<number | null>(null);

  const enabledModels = providers.filter((p) => p.enabled).flatMap((p) => p.models);

  const handleNameChange = (v: string) => {
    setName(v);
    const hit = PROVIDER_URLS.find((p) => p.match.test(v));
    if (hit && (!url.trim() || PROVIDER_URLS.some((p) => p.url === url.trim()))) {
      setUrl(hit.url);
    }
  };

  const handleAdd = () => {
    if (!name.trim() || !url.trim() || !key.trim()) {
      toast.error("请填写名称、端点 URL 和密钥");
      return;
    }
    if (!/^https?:\/\//.test(url.trim())) {
      toast.error("端点 URL 需以 http(s):// 开头");
      return;
    }
    const hit = PROVIDER_URLS.find((p) => p.url === url.trim());
    addProvider({ name, url, key, models: hit?.models });
    toast.success(`已接入「${name.trim()}」，测试连通后即可使用`);
    setName(""); setUrl(""); setKey("");
  };

  const test = (id: number, pingUrl: string) => {
    setTesting(id);
    markStatus(id, "untested");
    setTimeout(() => {
      setTesting(null);
      const ok = /^https?:\/\//.test(pingUrl);
      markStatus(id, ok ? "ok" : "fail");
      toast[ok ? "success" : "error"](ok ? "连接成功，端点可用" : "连接失败，请检查 URL");
    }, 900);
  };

  return (
    <div className="space-y-6">
      {/* 默认模型 */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
        <div className="flex items-center gap-2 mb-1">
          <Zap className="w-4 h-4 text-yellow-500" />
          <span className="text-sm font-bold text-foreground">默认模型</span>
        </div>
        <p className="text-xs text-muted-foreground mb-3">对话页与自动任务未单独指定时使用的模型（当前：{defaultModel}）。</p>
        <Select value={defaultModel} onValueChange={setDefaultModel}>
          <SelectTrigger className="w-full max-w-sm h-10"><SelectValue /></SelectTrigger>
          <SelectContent>
            {enabledModels.length === 0 && <SelectItem value="未配置">未配置（先启用服务商）</SelectItem>}
            {enabledModels.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Provider list */}
      <div className="space-y-3">
        {providers.map((p) => (
          <div key={p.id} className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-4">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-sm font-bold text-foreground truncate">{p.name}</span>
                {p.status === "ok" && <span className="inline-flex items-center gap-1 text-[10px] text-green-600 dark:text-green-400"><CheckCircle2 className="w-3 h-3" /> 连通</span>}
                {p.status === "fail" && <span className="inline-flex items-center gap-1 text-[10px] text-red-600 dark:text-red-400"><XCircle className="w-3 h-3" /> 失败</span>}
                {!p.enabled && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">已停用</span>}
              </div>
              <div className="flex items-center gap-1.5 shrink-0">
                <Button variant="outline" size="sm" className="h-7 text-[11px] px-2" onClick={() => test(p.id, p.url)} disabled={testing === p.id}>
                  {testing === p.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <Globe className="w-3 h-3" />} 测试
                </Button>
                <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground" onClick={() => setRevealed((s) => ({ ...s, [p.id]: !s[p.id] }))} title="显示/隐藏密钥">
                  {revealed[p.id] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                </Button>
                <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground hover:text-destructive" onClick={() => { removeProvider(p.id); toast.success("已移除"); }} title="移除">
                  <Trash2 className="w-3.5 h-3.5" />
                </Button>
                <Switch checked={p.enabled} onCheckedChange={() => toggleEnabled(p.id)} />
              </div>
            </div>
            <div className="text-[11px] font-mono text-muted-foreground truncate">{p.url}</div>
            <div className="text-[11px] font-mono text-muted-foreground">{p.masked}</div>
            <div className="flex flex-wrap gap-1.5 mt-2">
              {p.models.map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => { if (p.enabled) { setDefaultModel(m); toast.success(`默认模型已切换为 ${m}`); } }}
                  className={`px-2 py-0.5 rounded-full text-[10px] font-medium border cursor-pointer transition-colors ${defaultModel === m ? "bg-primary/10 text-primary border-primary/30" : "bg-muted/50 text-muted-foreground border-border hover:border-primary/40"}`}
                >
                  {m}{defaultModel === m ? " ·默认" : ""}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* Add provider */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5 space-y-4">
        <div>
          <div className="flex items-center gap-2">
            <Plus className="w-4 h-4 text-primary" />
            <span className="text-sm font-bold text-foreground">接入新服务商</span>
          </div>
          <p className="text-xs text-muted-foreground mt-1">输入常见服务商名称自动带出官方端点；使用代理或私有部署时手动修改 URL。</p>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">服务商名称</label>
            <Input placeholder="如 Anthropic / OpenAI" className="h-10 text-sm" value={name} onChange={(e) => handleNameChange(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">端点 URL</label>
            <Input placeholder="https://api.anthropic.com/v1" className="h-10 text-sm font-mono" value={url} onChange={(e) => setUrl(e.target.value)} />
          </div>
        </div>
        <div className="flex items-end gap-3">
          <div className="space-y-1.5 flex-1">
            <label className="text-xs font-bold text-foreground">API 密钥</label>
            <Input placeholder="粘贴密钥（本地脱敏存储）" className="h-10 text-sm font-mono" value={key} onChange={(e) => setKey(e.target.value)} />
          </div>
          <Button className="h-10 px-5 text-sm shrink-0" onClick={handleAdd}>
            <Plus className="w-4 h-4 mr-1" /> 接入
          </Button>
        </div>
      </div>
    </div>
  );
}
