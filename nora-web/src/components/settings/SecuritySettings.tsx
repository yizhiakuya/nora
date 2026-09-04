'use client';

import { ShieldCheck, Cpu, ArrowRight } from "lucide-react";
import { useRouter } from "next/navigation";

export function SecuritySettings() {
  const router = useRouter();

  return (
    <div className="space-y-6">
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        <div className="p-6 space-y-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-green-600 dark:text-green-400" />
            <label className="text-sm font-bold text-foreground">数据隐私</label>
          </div>
          {[
            "所有文件与索引数据仅存储在本地工作区，不上传云端。",
            "模型密钥仅保存在本地浏览器，用于向所配端点发起请求。",
            "密钥类环境变量（secret）自动脱敏，AI 读取时自动跳过。",
          ].map((line) => (
            <div key={line} className="flex items-start gap-2 text-xs text-muted-foreground">
              <ShieldCheck className="w-3 h-3 text-green-500 mt-0.5 shrink-0" />
              {line}
            </div>
          ))}
        </div>
      </div>

      <button
        type="button"
        onClick={() => router.push("/settings?tab=" + encodeURIComponent("模型管理"))}
        className="w-full bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5 flex items-center justify-between cursor-pointer hover:border-primary/40 transition-colors text-left"
      >
        <div className="flex items-center gap-3">
          <Cpu className="w-4 h-4 text-primary" />
          <div>
            <div className="text-sm font-bold text-foreground">模型服务商接入</div>
            <div className="text-xs text-muted-foreground mt-0.5">在设置 → 模型管理 中配置（名称 + 端点 URL + 密钥）</div>
          </div>
        </div>
        <ArrowRight className="w-4 h-4 text-muted-foreground" />
      </button>
    </div>
  );
}
