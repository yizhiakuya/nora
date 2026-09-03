'use client';

import { toast } from "sonner";
import { Check, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";

const USAGE = [
  { label: "本地存储",       used: "12.5 GB", total: "50 GB",  pct: 25 },
  { label: "知识库 chunks",  used: "1,847",   total: "5,000",  pct: 37 },
  { label: "本月模型调用",   used: "128 次",  total: "1,000 次", pct: 13 },
];

const FREE = ["3 个数据源连接", "1,000 chunks 向量索引", "每日 50 次模型调用", "基础清洗规则"];
const PRO = ["无限数据源连接", "无限 chunks + 关系图谱", "更高模型调用配额", "高级清洗规则 / 自定义嵌入模型", "环境控制台完整功能"];

export function BillingSettings() {
  const upgrade = () => toast.info("演示环境：订阅流程未接入");

  return (
    <div className="space-y-6">
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
        <div className="p-6 space-y-5">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-bold text-foreground">当前方案：个人版（免费）</div>
              <div className="text-xs text-muted-foreground mt-0.5">长期免费 · 无需绑定支付方式</div>
            </div>
            <span className="text-[10px] font-bold px-2 py-1 rounded-full bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border border-blue-100 dark:border-blue-900">进行中</span>
          </div>
          <div className="space-y-3">
            {USAGE.map((u) => (
              <div key={u.label}>
                <div className="flex items-center justify-between text-xs mb-1.5">
                  <span className="text-muted-foreground">{u.label}</span>
                  <span className="text-foreground tabular-nums">{u.used} / {u.total}</span>
                </div>
                <div className="w-full h-2 bg-muted rounded-full overflow-hidden">
                  <div className="h-full bg-primary rounded-full" style={{ width: `${u.pct}%` }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
          <div className="text-sm font-bold text-foreground mb-3">免费版</div>
          <div className="space-y-2">
            {FREE.map((f) => (
              <div key={f} className="flex items-start gap-2 text-xs text-muted-foreground">
                <Check className="w-3 h-3 text-green-500 mt-0.5 shrink-0" /> {f}
              </div>
            ))}
          </div>
        </div>
        <div className="bg-card dark:bg-card rounded-xl border-2 border-primary/40 shadow-sm p-5 relative">
          <span className="absolute -top-2.5 right-4 inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-primary text-primary-foreground text-[10px] font-bold">
            <Sparkles className="w-2.5 h-2.5" /> 推荐
          </span>
          <div className="text-sm font-bold text-foreground mb-3">专业版 <span className="text-xs text-muted-foreground font-normal">¥29/月</span></div>
          <div className="space-y-2">
            {PRO.map((f) => (
              <div key={f} className="flex items-start gap-2 text-xs text-foreground">
                <Check className="w-3 h-3 text-primary mt-0.5 shrink-0" /> {f}
              </div>
            ))}
          </div>
          <Button size="sm" className="w-full mt-4 h-9 text-xs" onClick={upgrade}>升级到专业版</Button>
        </div>
      </div>
    </div>
  );
}
