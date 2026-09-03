'use client';

import { useState } from "react";
import { toast } from "sonner";
import { Eye, EyeOff, Copy, Check, Save, KeyRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MOCK_ENV_VARS, EnvVar } from "@/lib/devData";

export function EnvEditor() {
  const [vars, setVars] = useState<EnvVar[]>(MOCK_ENV_VARS);
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  const updateValue = (key: string, value: string) => {
    setVars((prev) => prev.map((v) => (v.key === key ? { ...v, value } : v)));
    setDirty(true);
  };

  const toggleReveal = (key: string) => {
    setRevealed((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const copy = async (v: EnvVar) => {
    try {
      await navigator.clipboard.writeText(v.secret && !revealed[v.key] ? "••••（已脱敏，请先显示）" : v.value);
      setCopied(v.key);
      setTimeout(() => setCopied(null), 1200);
    } catch {
      toast.error("复制失败，请手动选择");
    }
  };

  const save = () => {
    setDirty(false);
    toast.success("环境变量已保存（.env.development）");
  };

  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl overflow-hidden">
      <div className="px-4 py-2.5 border-b border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-950/50 flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <KeyRound className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" />
          <span className="text-xs font-bold text-gray-700 dark:text-gray-200 font-mono">.env.development</span>
        </div>
        <Button size="sm" className={`h-7 text-[11px] px-3 ${dirty ? "bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" : ""}`} variant={dirty ? "default" : "outline"} onClick={save} disabled={!dirty}>
          <Save className="w-3 h-3 mr-1" /> 保存
        </Button>
      </div>

      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {vars.map((v) => (
          <div key={v.key} className="flex items-center gap-3 px-4 py-2.5">
            <div className="w-44 shrink-0">
              <div className="text-xs font-mono font-bold text-gray-800 dark:text-gray-100 truncate">{v.key}</div>
              {v.comment && <div className="text-[10px] text-gray-400 dark:text-gray-500 truncate">{v.comment}</div>}
            </div>
            <div className="flex-1 flex items-center gap-1.5">
              <input
                type="text"
                value={v.value}
                readOnly={v.secret}
                onChange={(e) => updateValue(v.key, e.target.value)}
                className="w-full bg-gray-50 dark:bg-gray-950 border border-gray-200 dark:border-gray-800 rounded-md px-2.5 py-1.5 text-xs font-mono text-gray-700 dark:text-gray-200 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-500"
              />
              {v.secret && (
                <Button variant="ghost" size="icon" className="w-7 h-7 shrink-0 text-gray-400 dark:text-gray-500" title={revealed[v.key] ? "隐藏" : "显示"} onClick={() => toggleReveal(v.key)}>
                  {revealed[v.key] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                </Button>
              )}
              <Button variant="ghost" size="icon" className="w-7 h-7 shrink-0 text-gray-400 dark:text-gray-500" title="复制" onClick={() => copy(v)}>
                {copied === v.key ? <Check className="w-3.5 h-3.5 text-green-500" /> : <Copy className="w-3.5 h-3.5" />}
              </Button>
            </div>
          </div>
        ))}
      </div>

      <div className="px-4 py-2 border-t border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-950/50">
        <span className="text-[10px] text-gray-400 dark:text-gray-500">
          密钥类变量已脱敏存储；AI 读取环境变量时自动跳过 secret 项。
        </span>
      </div>
    </div>
  );
}
