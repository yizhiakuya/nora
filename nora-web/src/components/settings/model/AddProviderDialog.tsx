'use client';

import { useState, useEffect } from "react";
import { toast } from "sonner";
import { Plus, Pencil } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useModelProviders, PROTOCOL_META, type ProviderProtocol, type ModelProvider } from "@/hooks/useModelProviders";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

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

export function AddProviderDialog({ isOpen, onClose, editing }: AddProviderDialogProps) {
  const addProvider = useModelProviders((s) => s.addProvider);
  const editProvider = useModelProviders((s) => s.editProvider);
  const isEdit = !!editing;

  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [key, setKey] = useState("");
  const [protocol, setProtocol] = useState<ProviderProtocol>("openai");

  // 编辑模式：打开时回填；新增模式：重置
  useEffect(() => {
    if (!isOpen) return;
    if (editing) {
      setName(editing.name);
      setUrl(editing.url);
      setKey("");
      setProtocol(editing.protocol);
    } else {
      setName(""); setUrl(""); setKey("");
      setProtocol("openai");
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

  const handleSubmit = () => {
    if (!name.trim() || !url.trim() || (!isEdit && !key.trim())) {
      toast.error(isEdit ? "请填写名称和端点 URL" : "请填写名称、端点 URL 和密钥");
      return;
    }
    if (!/^https?:\/\//.test(url.trim())) {
      toast.error("端点 URL 需以 http(s):// 开头");
      return;
    }
    if (isEdit && editing) {
      editProvider(editing.id, { name, url, key: key.trim() || undefined, protocol });
      toast.success(`已更新「${name.trim()}」，重新测试连通后生效`);
    } else {
      const hit = PROVIDER_PRESETS.find((p) => p.url === url.trim());
      addProvider({ name, url, key, protocol, models: hit?.models });
      toast.success(`已接入「${name.trim()}」，测试连通后即可使用`);
    }
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={isEdit ? `编辑服务商 · ${editing.name}` : "接入新服务商"} width="w-[90%] sm:w-[500px]"
      footer={
        <>
          <Button variant="outline" size="sm" className="h-9" onClick={onClose}>取消</Button>
          <Button size="sm" className="h-9" onClick={handleSubmit}>
            {isEdit ? <Pencil className="w-4 h-4 mr-1" /> : <Plus className="w-4 h-4 mr-1" />}
            {isEdit ? "保存修改" : "接入"}
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
          <label className="text-xs font-bold text-foreground">协议类型</label>
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
          <p className="text-[10px] text-muted-foreground">{PROTOCOL_META[protocol].desc}</p>
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">端点 URL</label>
          <Input
            placeholder="https://api.anthropic.com/v1"
            className="h-10 text-sm font-mono"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">API 密钥</label>
          <Input
            placeholder={isEdit ? "留空则保持原密钥不变" : "粘贴密钥（本地脱敏存储）"}
            className="h-10 text-sm font-mono"
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
          <p className="text-[10px] text-muted-foreground">
            {isEdit ? "修改端点或密钥后需重新测试连通" : "密钥仅保存在本机浏览器，不会上传云端"}
          </p>
        </div>
      </div>
    </Modal>
  );
}




