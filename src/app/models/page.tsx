'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Cpu, Plus, Trash2, Eye, EyeOff, CheckCircle2, XCircle, Loader2, Zap, Globe } from "lucide-react";
import { Header } from "@/components/layout/Header";
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

export default function ModelsPage() {
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
    <>
      <Header
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "模型管理", isCurrent: true }]}
        actions={<span className="text-xs text-gray-400 dark:text-gray-500 hidden sm:inline">{providers.length} 个服务商</span>}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950">
        <div className="max-w-4xl mx-auto space-y-6 pb-20">
          <div className="animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
              <Cpu className="w-5 h-5 text-blue-600 dark:text-blue-400" /> 模型管理
            </h1>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              配置对话与自动任务使用的 LLM 服务商：接入配置 = 名称 + 端点 URL + 密钥。
            </p>
          </div>

          {/* 默认模型 */}
          <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl p-5 animate-in fade-in slide-in-from-bottom-4">
            <div className="flex items-center gap-2 mb-1">
              <Zap className="w-4 h-4 text-yellow-500" />
              <span className="text-sm font-bold text-gray-800 dark:text-gray-100">默认模型</span>
            </div>
            <p className="text-xs text-gray-500 dark:text-gray-400 mb-3">对话页与自动任务未单独指定时使用的模型。</p>
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
              <div key={p.id} className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl p-4">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className="text-sm font-bold text-gray-800 dark:text-gray-100 truncate">{p.name}</span>
                    {p.status === "ok" && <span className="inline-flex items-center gap-1 text-[10px] text-green-600 dark:text-green-400"><CheckCircle2 className="w-3 h-3" /> 连通</span>}
                    {p.status === "fail" && <span className="inline-flex items-center gap-1 text-[10px] text-red-600 dark:text-red-400"><XCircle className="w-3 h-3" /> 失败</span>}
                    {!p.enabled && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-800 text-gray-400 dark:text-gray-500">已停用</span>}
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <Button variant="outline" size="sm" className="h-7 text-[11px] px-2" onClick={() => test(p.id, p.url)} disabled={testing === p.id}>
                      {testing === p.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <Globe className="w-3 h-3" />} 测试
                    </Button>
                    <Button variant="ghost" size="icon" className="w-7 h-7 text-gray-400" onClick={() => setRevealed((s) => ({ ...s, [p.id]: !s[p.id] }))} title="显示/隐藏密钥">
                      {revealed[p.id] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                    </Button>
                    <Button variant="ghost" size="icon" className="w-7 h-7 text-gray-400 hover:text-red-500" onClick={() => { removeProvider(p.id); toast.success("已移除"); }} title="移除">
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                    <Switch checked={p.enabled} onCheckedChange={() => toggleEnabled(p.id)} />
                  </div>
                </div>
                <div className="text-[11px] font-mono text-gray-500 dark:text-gray-400 truncate">{p.url}</div>
                <div className="text-[11px] font-mono text-gray-400 dark:text-gray-500">{revealed[p.id] ? p.masked.replace(/•+/, "demo-key") : p.masked}</div>
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {p.models.map((m) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => { if (p.enabled) { setDefaultModel(m); toast.success(`默认模型已切换为 ${m}`); } }}
                      className={`px-2 py-0.5 rounded-full text-[10px] font-medium border cursor-pointer transition-colors ${defaultModel === m ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800" : "bg-gray-50 dark:bg-gray-950 text-gray-500 dark:text-gray-400 border-gray-200 dark:border-gray-800 hover:border-blue-300"}`}
                    >
                      {m}{defaultModel === m ? " ·默认" : ""}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>

          {/* Add provider */}
          <div className="bg-white dark:bg-gray-900 border border-dashed border-gray-300 dark:border-gray-700 rounded-xl p-5 space-y-3 animate-in fade-in">
            <div className="flex items-center gap-2">
              <Plus className="w-4 h-4 text-primary" />
              <span className="text-sm font-bold text-foreground">接入新服务商</span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <Input placeholder="服务商名称（如 Anthropic，自动带出端点）" className="h-9 text-sm" value={name} onChange={(e) => handleNameChange(e.target.value)} />
              <Input placeholder="Base URL" className="h-9 text-sm font-mono" value={url} onChange={(e) => setUrl(e.target.value)} />
            </div>
            <div className="flex gap-2">
              <Input placeholder="密钥（本地脱敏存储）" className="h-9 text-sm font-mono flex-1" value={key} onChange={(e) => setKey(e.target.value)} />
              <Button size="sm" className="h-9 px-4 text-xs shrink-0" onClick={handleAdd}>接入</Button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
