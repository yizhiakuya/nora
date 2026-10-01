'use client';

import { useState, useEffect } from "react";
import { toast } from "sonner";
import { Plus, Pencil, RefreshCw, TriangleAlert } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { useModelProviders, PROTOCOL_META, type ProviderProtocol, type ModelProvider, type ModelSettings } from "@/hooks/useModelProviders";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { modelsApi } from "@/lib/services/modelsApi";

const PROVIDER_PRESETS: { match: RegExp; label: string; url: string; models: string[] }[] = [
  { match: /openai/i,     label: "OpenAI",     url: "https://api.openai.com/v1",                           models: ["GPT-4o", "GPT-4o mini", "o3-mini"] },
  { match: /anthropic/i,  label: "Anthropic",  url: "https://api.anthropic.com/v1",                        models: ["Claude 3.5 Sonnet", "Claude 3.5 Haiku"] },
  { match: /gemini|google/i, label: "Gemini",  url: "https://generativelanguage.googleapis.com/v1beta",    models: ["Gemini 1.5 Pro", "Gemini 1.5 Flash"] },
  { match: /deepseek/i,   label: "DeepSeek",   url: "https://api.deepseek.com/v1",                         models: ["deepseek-chat", "deepseek-reasoner"] },
  { match: /ollama|本地/i, label: "Ollama",     url: "http://localhost:11434/v1",                           models: ["qwen2.5:7b", "llama3.1:8b"] },
];

interface AddProviderDialogProps {
  isOpen: boolean;
  onClose: () => void;
  /** 传入 = 编辑模式（回填该服务商，密钥留空保持原值） */
  editing?: ModelProvider | null;
}

/**
 * 模糊匹配:query 的字符按序出现在 target 中即命中(子序列)。
 * 忽略大小写与常见分隔符(-_./@ :),「dsflash」能命中「DeepSeek-V4-Flash」。
 */
export function fuzzyMatch(target: string, query: string): boolean {
  if (!query) return true;
  const clean = (s: string) => s.toLowerCase().replace(/[-_./@:\s]/g, "");
  const t = clean(target);
  const q = clean(query);
  if (!q) return true;
  let ti = 0;
  for (const ch of q) {
    ti = t.indexOf(ch, ti);
    if (ti === -1) return false;
    ti += 1;
  }
  return true;
}

function fuzzyFilter(models: string[], query: string): string[] {
  if (!query.trim()) return models;
  return models.filter((m) => fuzzyMatch(m, query));
}

/**
 * 服务商接入/编辑弹窗。
 * 新增模式三步:基本信息 → 获取模型列表(连通测试顺便拉取) → 勾选要启用的模型并可逐个覆盖协议。
 * 编辑模式:改名称/协议/端点/密钥;密钥留空保持原值。
 */
export function AddProviderDialog({ isOpen, onClose, editing }: AddProviderDialogProps) {
  const addProvider = useModelProviders((s) => s.addProvider);
  const editProvider = useModelProviders((s) => s.editProvider);
  const isEdit = !!editing;

  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [key, setKey] = useState("");
  const [protocol, setProtocol] = useState<ProviderProtocol>("openai");

  // 模型发现:discovered = 上游全部模型;selected = 勾选启用的;modelProtocols = 逐模型协议覆盖
  const [discovered, setDiscovered] = useState<string[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [modelProtocols, setModelProtocols] = useState<Record<string, ProviderProtocol>>({});
  const [fetching, setFetching] = useState(false);
  /** 模型搜索框输入(模糊筛选,不影响已勾选集合) */
  const [modelFilter, setModelFilter] = useState("");
  /** 提交中(防重复点击,等待后端落库后才关弹窗) */
  const [saving, setSaving] = useState(false);

  // 编辑模式：打开时回填；新增模式：重置
  useEffect(() => {
    if (!isOpen) return;
    if (editing) {
      setName(editing.name);
      setUrl(editing.url);
      setKey("");
      setProtocol(editing.protocol);
      setDiscovered(editing.models.length > 0 ? editing.models : null);
      setSelected(new Set(editing.models));
      // 回填逐模型协议覆盖:此前固定置空,已存的覆盖在表单里看不见,
      // 用户看到的是"服务商默认协议"、实际跑的是被覆盖的那个(实测踩过:
      // deepseek-v4.1-flash 存的是 responses,UI 却显示 Chat Completions)。
      // 保存时只提交「与默认协议不同」的覆盖,未改动的不会丢。
      const overrides: Record<string, ProviderProtocol> = {};
      for (const m of editing.models) {
        const p = editing.modelSettings?.[m]?.protocol;
        if (p && p !== editing.protocol) overrides[m] = p;
      }
      setModelProtocols(overrides);
    } else {
      setName(""); setUrl(""); setKey("");
      setProtocol("openai");
      setDiscovered(null); setSelected(new Set()); setModelProtocols({});
    }
  }, [isOpen, editing]);

  const handleNameChange = (v: string) => {
    setName(v);
    if (isEdit) return; // 编辑时不自动带出，避免覆盖用户已改的值
    const hit = PROVIDER_PRESETS.find((p) => p.match.test(v));
    if (hit) {
      setUrl(hit.url);
      if (hit.match.source.includes("anthropic")) setProtocol("anthropic");
      else if (hit.match.source.includes("ollama")) setProtocol("ollama");
      else setProtocol("openai");
    }
  };

  /** 按当前表单发现模型;探测不创建或修改服务商记录。 */
  const fetchModels = async () => {
    if (!url.trim() || (!key.trim() && !isEdit)) {
      toast.error("请先填写端点 URL 和密钥");
      return;
    }
    setFetching(true);
    try {
      const result = await modelsApi.probeProvider({
        endpoint: url.trim(), apiKey: key.trim(), providerId: editing?.id,
      });
      const list = result.models;
      if (list && list.length > 0) {
        setDiscovered(list);
        setSelected(new Set(list));
        toast.success(`发现 ${list.length} 个模型，请勾选要启用的`);
      } else {
        toast.error("未发现任何模型，请检查端点地址（通常以 /v1 结尾）");
      }
    } catch (e) {
      toast.error(`获取失败: ${(e as Error).message}`);
    } finally {
      setFetching(false);
    }
  };

  const toggleModel = (m: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(m)) next.delete(m);
      else next.add(m);
      return next;
    });
  };

  const setAllModels = (on: boolean) => {
    if (discovered) setSelected(on ? new Set(discovered) : new Set());
  };

  const handleSubmit = async () => {
    if (saving) return;
    if (!name.trim() || !url.trim() || (!isEdit && !key.trim())) {
      toast.error(isEdit ? "请填写名称和端点 URL" : "请填写名称、端点 URL 和密钥");
      return;
    }
    if (!/^https?:\/\//.test(url.trim())) {
      toast.error("端点 URL 需以 http(s):// 开头");
      return;
    }
    const chosenModels = discovered ? Array.from(selected) : undefined;
    // 逐模型协议覆盖:以已存 modelSettings 为基底合并,只写 protocol。
    // 后端 PUT 对 modelSettings 是整体替换(见 ModelProviderService.update),
    // 若只从 modelProtocols 重建,会把该模型已配的 vision/reasoningLevels/
    // contextWindow 全部抹掉。此处保留基底,协议清空时回退为 provider 默认。
    const modelSettings = (() => {
      if (!chosenModels) return undefined;
      const base = editing?.modelSettings ?? {};
      const next: ModelSettings = { ...base };
      let touched = false;
      for (const m of chosenModels) {
        const picked = modelProtocols[m];
        const stored = base[m]?.protocol;
        if (picked && picked !== protocol) {
          next[m] = { ...(base[m] ?? {}), protocol: picked };
          touched = true;
        } else if (stored && picked !== stored) {
          // 用户明确把该模型下拉选成了「跟随默认」（与已存覆盖不同）:删掉覆盖,保留其他设置。
          // 判据必须是 picked !== stored(用户动过下拉),不能用 picked === protocol——
          // 后者会把「没动过下拉、但默认协议恰好被改成与覆盖同值」的模型误判为主动清覆盖
          // （实测踩过:deepseek 覆盖 anthropic、用户把默认协议也改成 anthropic,覆盖被
          // 静默删掉）。stored 为 undefined 时不动:回填未产生覆盖,也无从"清"。
          const { protocol: _drop, ...rest } = base[m];
          next[m] = rest;
          touched = true;
        }
      }
      return touched ? next : undefined;
    })();

    setSaving(true);
    try {
      if (isEdit && editing) {
        await editProvider(editing.id, {
          name, url, key, protocol, models: chosenModels, modelSettings,
        });
        toast.success(`已更新「${name.trim()}」`);
      } else {
        const hit = PROVIDER_PRESETS.find((p) => p.url === url.trim());
        await addProvider({
          name, url, key, protocol,
          models: chosenModels ?? hit?.models,
          ...(modelSettings ? { modelSettings } : {}),
        });
        toast.success(`已接入「${name.trim()}」，测试连通后即可使用`);
      }
      onClose();
    } catch (e) {
      toast.error(`保存失败: ${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={isEdit ? `编辑服务商 · ${editing.name}` : "接入新服务商"} width="w-[90%] sm:w-[540px]"
      footer={
        <>
          <Button variant="outline" size="sm" className="h-9" onClick={onClose}>取消</Button>
          <Button size="sm" className="h-9" onClick={handleSubmit} disabled={saving || fetching}>
            {isEdit ? <Pencil className="w-4 h-4 mr-1" /> : <Plus className="w-4 h-4 mr-1" />}
            {saving ? "保存中…" : isEdit ? "保存修改" : discovered ? `接入（${selected.size} 个模型）` : "接入"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">服务商名称</label>
          <Input placeholder="如 Anthropic / OpenAI / Ollama" className="h-10 text-sm" value={name} onChange={(e) => handleNameChange(e.target.value)} />
          {!isEdit && <p className="text-[10px] text-muted-foreground">输入常见服务商名称自动带出官方端点与模型列表</p>}
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">默认协议</label>
          <Select value={protocol} onValueChange={(v) => setProtocol(v as ProviderProtocol)}>
            <SelectTrigger className="w-full h-10 rounded-lg"><SelectValue /></SelectTrigger>
            <SelectContent>
              {(Object.keys(PROTOCOL_META) as ProviderProtocol[]).map((key) => (
                <SelectItem key={key} value={key}>
                  {PROTOCOL_META[key].label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-[10px] text-muted-foreground">{PROTOCOL_META[protocol].desc}· 每个模型可单独覆盖（下方）</p>
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">端点 URL</label>
          <div className="flex gap-2">
            <Input
              placeholder="https://api.anthropic.com/v1"
              className="h-10 text-sm font-mono"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
            <Button variant="outline" size="sm" className="h-10 px-3 shrink-0" onClick={fetchModels} disabled={fetching}>
              {fetching ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              {fetching ? "获取中" : "获取模型"}
            </Button>
          </div>
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">API 密钥</label>
          <Input
            placeholder={isEdit ? "留空则保持原密钥不变" : "粘贴密钥（服务端保存,列表只显示脱敏预览）"}
            className="h-10 text-sm font-mono"
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
          <p className="text-[10px] text-muted-foreground">
            {isEdit ? "修改端点或密钥后需重新测试连通" : "密钥保存在 Nora 服务端,浏览器只缓存脱敏预览"}
          </p>
        </div>

        {/* 模型勾选区 */}
        {discovered && discovered.length > 0 && (
          <div className="space-y-2 rounded-lg border border-border p-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-foreground">启用模型（{selected.size}/{discovered.length}）</label>
              <div className="flex gap-2">
                <button type="button" className="text-[10px] text-muted-foreground hover:text-foreground cursor-pointer" onClick={() => setAllModels(true)}>全选</button>
                <button type="button" className="text-[10px] text-muted-foreground hover:text-foreground cursor-pointer" onClick={() => setAllModels(false)}>清空</button>
              </div>
            </div>
            <Input
              placeholder="搜索筛选模型（支持模糊匹配）"
              className="h-8 text-xs"
              value={modelFilter}
              onChange={(e) => setModelFilter(e.target.value)}
            />
            <div className="max-h-56 overflow-auto custom-scroll space-y-0.5">
              {fuzzyFilter(discovered, modelFilter).map((m) => {
                const checked = selected.has(m);
                const mp = modelProtocols[m] ?? protocol;
                return (
                  <div key={m} className={`flex items-center gap-2 px-2 py-1.5 rounded-md ${checked ? "bg-muted/50" : ""}`}>
                    <Checkbox checked={checked} onCheckedChange={() => toggleModel(m)} className="shrink-0 cursor-pointer" />
                    <span className="text-xs font-mono text-foreground truncate flex-1" title={m}>{m}</span>
                    <Select value={mp} onValueChange={(v) => setModelProtocols((prev) => ({ ...prev, [m]: v as ProviderProtocol }))}>
                      <SelectTrigger className="h-6 w-[150px] text-[10px] rounded-md shrink-0">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {(Object.keys(PROTOCOL_META) as ProviderProtocol[]).map((pk) => (
                          <SelectItem key={pk} value={pk} className="text-[11px]">{PROTOCOL_META[pk].label}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                );
              })}
              {fuzzyFilter(discovered, modelFilter).length === 0 && (
                <p className="text-[11px] text-muted-foreground text-center py-4">
                  无匹配「{modelFilter}」的模型
                </p>
              )}
            </div>
            {selected.size === 0 && (
              <p className="text-[10px] text-amber-600 dark:text-amber-400 flex items-center gap-1">
                <TriangleAlert className="w-3 h-3" /> 至少勾选一个模型，否则该服务商不可用
              </p>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
