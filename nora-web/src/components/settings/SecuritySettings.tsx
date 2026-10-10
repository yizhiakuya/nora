'use client';

import { ShieldCheck, Cpu, ArrowRight } from "lucide-react";
import { useNavigate } from "react-router-dom";

export function SecuritySettings() {
  const navigate = useNavigate();

  return (
    <div className="space-y-6">
      <div className="surface overflow-hidden">
        <div className="p-5 sm:p-6 space-y-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-green-600 dark:text-green-400" />
            <label className="text-sm font-semibold text-foreground">数据隐私</label>
          </div>
          {[
            "文件、索引和会话存于 Nora 部署的服务器；调用模型或索引服务时，相关内容会发送到已配置的服务商。",
            "模型密钥保存在服务端，管理页面只返回脱敏值。",
            "密钥类环境变量读取时脱敏；执行命令时注入子进程，不写入模型上下文。",
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
        onClick={() => navigate("/agent?section=model")}
        className="w-full surface p-5 flex items-center justify-between cursor-pointer hover:border-primary/40 transition-colors text-left"
      >
        <div className="flex items-center gap-3">
          <Cpu className="w-4 h-4 text-primary" />
          <div>
            <div className="text-sm font-semibold text-foreground">模型服务商接入</div>
            <div className="text-xs text-muted-foreground mt-0.5">在 Agent → 模型 中管理服务商与密钥</div>
          </div>
        </div>
        <ArrowRight className="w-4 h-4 text-muted-foreground" />
      </button>
    </div>
  );
}
