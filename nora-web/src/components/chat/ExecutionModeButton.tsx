import { useEffect, useState } from "react";
import { Code2, ChevronDown } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel } from "@/components/ui/dropdown-menu";
import { fetchExecutionMode, saveExecutionMode, type ExecutionModeSettings } from "@/lib/api/agentApi";
import { humanizeError } from "@/lib/errorMessages";

export function ExecutionModeButton({ sessionId }: { sessionId: string }) {
  const [settings, setSettings] = useState<ExecutionModeSettings>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchExecutionMode(sessionId).then(value => { if (active) { setSettings(value); setError(undefined); } })
      .catch(failure => { if (active) setError(humanizeError(failure).message); });
    return () => { active = false; };
  }, [sessionId, revision]);
  const save = async (mode: "standard" | "ptc") => {
    setSaving(true);
    try { setSettings(await saveExecutionMode(sessionId, mode)); }
    catch (failure) { toast.error(humanizeError(failure).message); setRevision(value => value + 1); }
    finally { setSaving(false); }
  };
  if (error) return <div className="text-xs text-destructive" role="alert">{error}
    <Button variant="ghost" size="sm" onClick={() => setRevision(value => value + 1)}>重试读取执行模式</Button></div>;
  return <DropdownMenu>
    <DropdownMenuTrigger asChild><Button variant="ghost" size="sm" disabled={!settings || saving}
      className="h-7 gap-1 px-2 text-xs text-muted-foreground" aria-label="执行模式">
      <Code2 className="h-3.5 w-3.5" />{saving ? "保存中…" : !settings ? "读取模式…" : settings.executionMode === "ptc" ? "PTC" : "标准"}<ChevronDown className="h-3 w-3" />
    </Button></DropdownMenuTrigger>
    <DropdownMenuContent align="start" className="w-72">
      <DropdownMenuLabel className="text-xs text-muted-foreground">执行模式 · 保存后新消息生效</DropdownMenuLabel>
      <DropdownMenuItem onSelect={() => void save("standard")} className="flex-col items-start gap-1">
        <span>标准{settings?.executionMode === "standard" ? " ✓" : ""}</span><span className="text-xs text-muted-foreground">模型逐轮调用工具</span>
      </DropdownMenuItem>
      <DropdownMenuItem disabled={!settings?.ptcAvailable} onSelect={() => void save("ptc")} className="flex-col items-start gap-1">
        <span>PTC{settings?.executionMode === "ptc" ? " ✓" : ""}</span><span className="text-xs text-muted-foreground">{settings?.ptcAvailable ? "用程序串联、并行调用工具，权限逐项生效" : "PTC 运行环境不可用，请检查服务器配置"}</span>
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>;
}
