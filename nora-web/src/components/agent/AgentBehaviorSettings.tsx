import { useEffect, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAgentSettings } from "@/hooks/useAgentSettings";
import { useModelProviders, resolveDefaultProvider, REASONING_LEVELS } from "@/hooks/useModelProviders";
import { usePreferences } from "@/hooks/usePreferences";
import { PERMISSION_MODE_META, type PermissionMode } from "@/lib/api/chatApi";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

function SettingRow({ label, description, children, id }: { label: string; description: string; children: ReactNode; id: string }) {
  return <div className="flex flex-col gap-3 py-5 [@container(min-width:600px)]:flex-row [@container(min-width:600px)]:items-start [@container(min-width:600px)]:justify-between [@container(min-width:600px)]:gap-6">
    <div className="min-w-0 space-y-1 [@container(min-width:600px)]:max-w-md">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      <p className="text-xs leading-relaxed text-muted-foreground">{description}</p>
    </div>
    <div className="w-full shrink-0 [@container(min-width:600px)]:w-64">{children}</div>
  </div>;
}

export function AgentBehaviorSettings() {
  const { permissionMode, reasoningLevelOverride, commandTimeoutSeconds, setCommandTimeoutSeconds, ready, loading, saving, error, setPermissionMode, setReasoningLevelOverride, syncFromBackend } = useAgentSettings();
  const { providers, defaultModel, defaultProviderId, setDefaultModel, syncFromBackend: syncModels } = useModelProviders();
  const summary = usePreferences(s => s.reasoningSummary);
  const setSummary = usePreferences(s => s.setReasoningSummary);
  useEffect(() => { void syncFromBackend(); void syncModels(); }, [syncFromBackend, syncModels]);
  const provider = resolveDefaultProvider(providers, defaultModel, defaultProviderId);
  const models = providers.filter(p => p.enabled).flatMap(p => p.models.map(model => ({
    value: JSON.stringify([p.id, model]), model, provider: p,
  })));
  const configured = provider?.modelSettings?.[defaultModel]?.reasoningLevels ?? [];
  const levels = configured.length ? REASONING_LEVELS.filter(level => configured.includes(level)) : REASONING_LEVELS;
  const busy = !ready || loading || saving;

  return <section className="surface [container-type:inline-size] px-5 sm:px-6">
    <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border py-5">
      <h2 className="section-heading">默认行为</h2>
      <span aria-live="polite" className="text-xs text-muted-foreground">{saving ? "保存中…" : loading ? "加载中…" : "更改后自动保存"}</span>
    </div>
    {error && <div className="flex flex-wrap items-center gap-3 pt-4">
      <p role="alert" className="min-w-0 flex-1 whitespace-pre-wrap break-words text-xs text-destructive">{error}</p>
      <Button variant="outline" size="sm" onClick={() => void syncFromBackend()}>重试</Button>
    </div>}
    <div className="divide-y divide-border">
      <SettingRow id="agent-model" label="默认模型" description="用于当前浏览器中的后续对话。聊天底部也可以直接切换。">
        <Select value={provider ? JSON.stringify([provider.id, defaultModel]) : ""} disabled={!models.length}
          onValueChange={value => { const choice = models.find(model => model.value === value); if (choice) setDefaultModel(choice.model, choice.provider.id); }}>
          <SelectTrigger id="agent-model"><SelectValue placeholder="尚未配置模型" /></SelectTrigger>
          <SelectContent>{models.map(choice => <SelectItem key={choice.value} value={choice.value}>{choice.model} · {choice.provider.name}</SelectItem>)}</SelectContent>
        </Select>
        <Link to="/agent?section=model" className="mt-2 inline-block text-xs text-primary hover:underline">管理模型与服务商</Link>
      </SettingRow>
      <SettingRow id="agent-reasoning" label="思考等级" description="自动时跟随模型配置。等级由服务商支持，输出额度遵循上游默认。">
        <Select value={reasoningLevelOverride ?? "auto"} disabled={busy} onValueChange={value => void setReasoningLevelOverride(value === "auto" ? undefined : value)}>
          <SelectTrigger id="agent-reasoning"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="auto">自动 · 跟随模型配置</SelectItem>
            {reasoningLevelOverride && !levels.includes(reasoningLevelOverride as typeof levels[number]) && <SelectItem value={reasoningLevelOverride} disabled>{reasoningLevelOverride}（当前模型未启用）</SelectItem>}
            {levels.map(level => <SelectItem key={level} value={level}>{level}</SelectItem>)}
          </SelectContent>
        </Select>
      </SettingRow>
      <SettingRow id="agent-permission" label="工具权限" description={PERMISSION_MODE_META[permissionMode].description}>
        <Select value={permissionMode} disabled={busy} onValueChange={value => void setPermissionMode(value as PermissionMode)}>
          <SelectTrigger id="agent-permission"><SelectValue /></SelectTrigger>
          <SelectContent>{(Object.keys(PERMISSION_MODE_META) as PermissionMode[]).map(mode => <SelectItem key={mode} value={mode}>{PERMISSION_MODE_META[mode].label}</SelectItem>)}</SelectContent>
        </Select>
      </SettingRow>
      <SettingRow id="agent-command-timeout" label="命令总时限" description="长命令默认等待 3 秒后转到后台。此处设置进程总运行时限，超时后停止进程树；模型可为某条命令显式指定其他时限。">
        <Select value={String(commandTimeoutSeconds)} disabled={busy} onValueChange={value => void setCommandTimeoutSeconds(Number(value))}>
          <SelectTrigger id="agent-command-timeout"><SelectValue /></SelectTrigger>
          <SelectContent>
            {![300, 600, 1800, 3600, 7200].includes(commandTimeoutSeconds) && <SelectItem value={String(commandTimeoutSeconds)}>{commandTimeoutSeconds} 秒</SelectItem>}
            {[300, 600, 1800, 3600, 7200].map(value => <SelectItem key={value} value={String(value)}>{value / 60} 分钟{value === 1800 ? " · 默认" : ""}</SelectItem>)}
          </SelectContent>
        </Select>
      </SettingRow>
      <SettingRow id="agent-summary" label="推理摘要" description="简洁默认收起工具和思考详情，详细自动展开。查看过的工作过程会保留展开状态。">
        <Select value={summary} onValueChange={value => { if (value === "concise" || value === "detailed") setSummary(value); }}>
          <SelectTrigger id="agent-summary"><SelectValue /></SelectTrigger>
          <SelectContent><SelectItem value="concise">简洁</SelectItem><SelectItem value="detailed">详细</SelectItem></SelectContent>
        </Select>
      </SettingRow>
    </div>
    <p className="border-t border-border py-4 text-[11px] leading-relaxed text-muted-foreground">权限和思考等级保存在服务端，应用到后续请求；模型选择和过程显示保存在当前浏览器。</p>
  </section>;
}
