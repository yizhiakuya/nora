'use client';

import { useState } from "react";
import { toast } from "sonner";
import { CheckCircle2, XCircle, Loader2, Globe, Eye, EyeOff, Trash2, Plus, Server, Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { EmptyState } from "@/components/ui/custom/States";
import { useModelProviders, PROTOCOL_META, type ModelProvider } from "@/hooks/useModelProviders";

function ProviderCard({ p, onEdit }: { p: ModelProvider; onEdit: (p: ModelProvider) => void }) {
  const toggleEnabled = useModelProviders((s) => s.toggleEnabled);
  const removeProvider = useModelProviders((s) => s.removeProvider);
  const markStatus = useModelProviders((s) => s.markStatus);
  const testProvider = useModelProviders((s) => s.testProvider);
  const [testing, setTesting] = useState(false);
  const [revealed, setRevealed] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [saving, setSaving] = useState(false);

  const changeProvider = async (remove: boolean) => {
    setSaving(true);
    try {
      if (remove) {
        await removeProvider(p.id);
        toast.success(`已移除「${p.name}」`);
      } else {
        await toggleEnabled(p.id);
      }
    } catch (e) {
      toast.error(`保存失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  };

  const test = async () => {
    setTesting(true);
    markStatus(p.id, "untested");
    try {
      const status = await testProvider(p.id);
      toast[status === "ok" ? "success" : "error"](
        status === "ok" ? "连接成功，端点可用" : "连接失败，请检查端点与密钥"
      );
    } catch (e) {
      // 后端异常:如实报错,不做本地启发式判定
      markStatus(p.id, "fail");
      toast.error(`连接测试失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      {/* 头部：名称 + 状态 + 启用开关 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border/60">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="w-8 h-8 rounded-lg bg-muted flex items-center justify-center shrink-0">
            <Server className="w-4 h-4 text-muted-foreground" />
          </div>
          <div className="min-w-0">
            <div className="text-sm font-bold text-foreground truncate">{p.name}</div>
            <div className="flex items-center gap-1.5 mt-0.5">
              {p.status === "ok" && (
                <span className="inline-flex items-center gap-1 text-[10px] text-green-600 dark:text-green-400">
                  <CheckCircle2 className="w-3 h-3" /> 已连通
                </span>
              )}
              {p.status === "fail" && (
                <span className="inline-flex items-center gap-1 text-[10px] text-red-600 dark:text-red-400">
                  <XCircle className="w-3 h-3" /> 连接失败
                </span>
              )}
              {p.status === "untested" && (
                <span className="text-[10px] text-muted-foreground">未测试</span>
              )}
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-primary/10 text-primary border border-primary/20">
                {PROTOCOL_META[p.protocol]?.label ?? p.protocol}
              </span>
              {!p.enabled && (
                <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">已停用</span>
              )}
            </div>
          </div>
        </div>
        <Switch checked={p.enabled} disabled={saving} onCheckedChange={() => changeProvider(false)} />
      </div>

      {/* 详情：URL + 密钥 + 操作 */}
      <div className="px-4 py-3 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0 flex-1">
            <div className="text-[10px] text-muted-foreground mb-0.5">端点 URL</div>
            <div className="text-xs font-mono text-foreground truncate">{p.url}</div>
          </div>
        </div>
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0 flex-1">
            <div className="text-[10px] text-muted-foreground mb-0.5">API 密钥</div>
            <div className="text-xs font-mono text-foreground truncate">{p.masked}</div>
          </div>
          <Button
            variant="ghost"
            size="icon"
            className="w-7 h-7 shrink-0 text-muted-foreground"
            onClick={() => setRevealed((s) => !s)}
            title={revealed ? "隐藏" : "显示"}
          >
            {revealed ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          </Button>
        </div>

        <div className="flex items-center gap-2 pt-1">
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-[11px] px-3"
            onClick={test}
            disabled={testing}
          >
            {testing ? <Loader2 className="w-3 h-3 animate-spin" /> : <Globe className="w-3 h-3" />}
            {testing ? "测试中…" : "测试连通"}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-[11px] px-2 text-muted-foreground hover:text-foreground"
            onClick={() => onEdit(p)}
            title="编辑服务商"
          >
            <Pencil className="w-3 h-3 mr-1" /> 编辑
          </Button>
          <span className="text-[10px] text-muted-foreground ml-auto hidden sm:inline">
            思考等级与上下文窗口在「模型列表」页按模型配置
          </span>
          {confirmRemove ? (
            <>
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-[11px] px-2 text-destructive hover:text-destructive hover:bg-destructive/10"
                disabled={saving}
                onClick={() => changeProvider(true)}
              >
                确认移除
              </Button>
              <Button variant="ghost" size="sm" className="h-7 text-[11px] px-2" onClick={() => setConfirmRemove(false)}>
                取消
              </Button>
            </>
          ) : (
            <Button
              variant="ghost"
              size="icon"
              className={`w-7 h-7 text-muted-foreground hover:text-destructive ${confirmRemove ? "" : "shrink-0"}`}
              onClick={() => setConfirmRemove(true)}
              title="移除服务商"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

export function ProvidersTab({ onAdd, onEdit }: { onAdd: () => void; onEdit: (p: ModelProvider) => void }) {
  const providers = useModelProviders((s) => s.providers);
  if (providers.length === 0) {
    return (
      <EmptyState
        icon={Server}
        title="还没有接入服务商"
        description="接入 OpenAI、Anthropic、DeepSeek 或本地 Ollama，模型才能在对话与任务中使用"
        action={
          <Button size="sm" onClick={onAdd}>
            <Plus className="w-4 h-4 mr-1.5" /> 接入服务商
          </Button>
        }
      />
    );
  }
  return (
    <div className="space-y-3">
      {providers.map((p) => <ProviderCard key={p.id} p={p} onEdit={onEdit} />)}
    </div>
  );
}

