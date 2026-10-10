'use client';

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { toast } from "sonner";
import { Save } from "lucide-react";
import { userPreferencesApi, type UserPreferences } from "@/lib/services/userPreferencesApi";

/**
 * 用户偏好设置(M2-05,2026-09-20,方案 §9):
 * 首版三项**能落地**的结构化偏好——报告语言 / 默认成果目录 / 命名习惯。
 * 保存到后端 app_setting,用户任务装配上下文时加载。
 */
export function UserPreferencesSettings() {
  const [prefs, setPrefs] = useState<UserPreferences>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setLoadError(null);
    (async () => {
      try {
        const loaded = await userPreferencesApi.get();
        if (!cancelled) setPrefs(loaded);
      } catch (error) {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [reload]);

  const save = async () => {
    setSaving(true);
    try {
      const saved = await userPreferencesApi.update(prefs);
      setPrefs(saved);
      toast.success("偏好已保存,将应用到新任务");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="surface p-6 text-xs text-muted-foreground">加载偏好…</div>;
  }

  if (loadError) return <section className="surface p-5 space-y-3">
    <h2 className="section-heading">我的偏好</h2>
    <p role="alert" className="whitespace-pre-wrap break-words text-xs text-destructive">{loadError}</p>
    <Button variant="outline" size="sm" onClick={() => setReload(v => v + 1)}>重试</Button>
  </section>;

  return (
    <div className="surface overflow-hidden">
      <div className="p-5 sm:p-6 space-y-5">
        <div>
          <h2 className="section-heading">我的偏好</h2>
          <p className="text-xs text-muted-foreground mt-1">
            设置成果的默认语言、保存位置和命名方式。对话中的要求会优先采用。
          </p>
        </div>

        <div className="space-y-1.5">
          <label htmlFor="preference-language" className="text-xs font-medium text-foreground">报告与成果默认语言</label>
          <Input
            id="preference-language"
            className="h-9"
            placeholder="例如：中文 / English"
            value={prefs.reportLanguage ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, reportLanguage: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">生成报告、整理结果时默认使用的语言。</p>
        </div>

        <div className="space-y-1.5">
          <label htmlFor="preference-directory" className="text-xs font-medium text-foreground">默认成果目录（工作区相对路径）</label>
          <Input
            id="preference-directory"
            className="h-9"
            placeholder="例如：reports"
            value={prefs.defaultOutputDir ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, defaultOutputDir: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">保存报告/导出文件时的默认落点(在「资料 → 工作区」可见)。</p>
        </div>

        <div className="space-y-1.5">
          <label htmlFor="preference-naming" className="text-xs font-medium text-foreground">文件命名习惯</label>
          <Input
            id="preference-naming"
            className="h-9"
            placeholder="例如：日期前缀 YYYYMMDD-名称"
            value={prefs.namingStyle ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, namingStyle: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">生成文件名时遵循的习惯。</p>
        </div>

        <div className="flex justify-end">
          <Button size="sm" className="h-8 text-xs bg-primary text-primary-foreground hover:bg-primary/90" onClick={save} disabled={saving}>
            <Save className="w-3.5 h-3.5 mr-1.5" /> {saving ? "保存中…" : "保存偏好"}
          </Button>
        </div>
      </div>
    </div>
  );
}
