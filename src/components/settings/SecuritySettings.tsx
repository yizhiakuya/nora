'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Key, Eye, EyeOff, Trash2, ShieldCheck, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

interface ApiKey { id: number; name: string; masked: string; url: string; }

/** 常用服务商 → 默认 Base URL：填名称自动带出，可改为代理/私有端点 */
const PROVIDER_URLS: { match: RegExp; url: string }[] = [
  { match: /openai/i,     url: "https://api.openai.com/v1" },
  { match: /anthropic/i,  url: "https://api.anthropic.com/v1" },
  { match: /github/i,     url: "https://api.github.com" },
  { match: /gemini|google/i, url: "https://generativelanguage.googleapis.com/v1beta" },
  { match: /deepseek/i,   url: "https://api.deepseek.com/v1" },
  { match: /ollama|本地/i, url: "http://localhost:11434/v1" },
];

const SEED_KEYS: ApiKey[] = [
  { id: 1, name: "OpenAI（模型 + Embedding）", masked: "sk-demo-••••••••4821", url: "https://api.openai.com/v1" },
  { id: 2, name: "GitHub（代码仓库接入）",     masked: "ghp_••••••••••9f2c",     url: "https://api.github.com" },
];

export function SecuritySettings() {
  const [keys, setKeys] = useState<ApiKey[]>(SEED_KEYS);
  const [revealed, setRevealed] = useState<Record<number, boolean>>({});
  const [newName, setNewName] = useState("");
  const [newUrl, setNewUrl] = useState("");
  const [newValue, setNewValue] = useState("");

  /** 名称匹配已知服务商时自动填端点（不覆盖用户手输的 URL） */
  const handleNameChange = (v: string) => {
    setNewName(v);
    const hit = PROVIDER_URLS.find((p) => p.match.test(v));
    if (hit && (!newUrl.trim() || PROVIDER_URLS.some((p) => p.url === newUrl.trim()))) {
      setNewUrl(hit.url);
    }
  };

  const addKey = () => {
    if (!newName.trim() || !newUrl.trim() || !newValue.trim()) {
      toast.error("请填写名称、端点 URL 和密钥");
      return;
    }
    if (!/^https?:\/\//.test(newUrl.trim())) {
      toast.error("端点 URL 需以 http(s):// 开头");
      return;
    }
    const masked = newValue.slice(0, 4) + "••••••••" + newValue.slice(-4);
    setKeys((prev) => [...prev, { id: Date.now(), name: newName.trim(), masked, url: newUrl.trim() }]);
    setNewName("");
    setNewUrl("");
    setNewValue("");
    toast.success("接入配置已保存：请求将发送到你填写的端点");
  };

  const removeKey = (id: number) => {
    setKeys((prev) => prev.filter((k) => k.id !== id));
    toast.success("密钥已移除");
  };

  return (
    <div className="space-y-6">
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        <div className="p-6 space-y-4">
          <div className="flex items-center gap-2">
            <Key className="w-4 h-4 text-primary" />
            <label className="text-sm font-bold text-foreground">API 密钥</label>
          </div>
          <div className="space-y-2">
            {keys.map((k) => (
              <div key={k.id} className="flex items-center gap-3 px-3 py-2.5 rounded-lg bg-muted/40 border border-border">
                <div className="min-w-0 flex-1">
                  <div className="text-xs font-medium text-foreground truncate">{k.name}</div>
                  <div className="text-[11px] font-mono text-muted-foreground truncate">{k.url}</div>
                  <div className="text-[11px] font-mono text-muted-foreground">{k.masked}</div>
                </div>
                <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground" onClick={() => setRevealed((p) => ({ ...p, [k.id]: !p[k.id] }))} title="显示/隐藏">
                  {revealed[k.id] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                </Button>
                <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground hover:text-destructive" onClick={() => removeKey(k.id)} title="移除">
                  <Trash2 className="w-3.5 h-3.5" />
                </Button>
              </div>
            ))}
          </div>

          <div className="space-y-2">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <Input placeholder="服务商名称（如 Anthropic，自动带出端点）" className="h-9 text-sm" value={newName} onChange={(e) => handleNameChange(e.target.value)} />
              <Input placeholder="Base URL（https://api.anthropic.com/v1）" className="h-9 text-sm font-mono" value={newUrl} onChange={(e) => setNewUrl(e.target.value)} />
            </div>
            <div className="flex flex-col sm:flex-row gap-2">
              <Input placeholder="粘贴密钥（本地脱敏存储）" className="h-9 text-sm font-mono flex-1" value={newValue} onChange={(e) => setNewValue(e.target.value)} />
              <Button size="sm" className="h-9 px-3 text-xs shrink-0" onClick={addKey}>
                <Plus className="w-3.5 h-3.5 mr-1" /> 添加
              </Button>
            </div>
            <p className="text-[10px] text-muted-foreground">
              接入配置 = 名称 + 端点 URL + 密钥。使用代理或私有部署时，把 URL 改成你的端点即可。
            </p>
          </div>
        </div>
      </div>

      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        <div className="p-6 space-y-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-green-600 dark:text-green-400" />
            <label className="text-sm font-bold text-foreground">数据隐私</label>
          </div>
          {[
            "所有文件与索引数据仅存储在本地工作区，不上传云端。",
            "密钥类环境变量（secret）自动脱敏，AI 读取时自动跳过。",
            "API 密钥仅保存在本地浏览器存储，用于发起模型请求。",
          ].map((line) => (
            <div key={line} className="flex items-start gap-2 text-xs text-muted-foreground">
              <ShieldCheck className="w-3 h-3 text-green-500 mt-0.5 shrink-0" />
              {line}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
