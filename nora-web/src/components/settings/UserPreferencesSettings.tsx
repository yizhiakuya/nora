'use client';

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { toast } from "sonner";
import { Save } from "lucide-react";
import { userPreferencesApi, type UserPreferences } from "@/lib/services/userPreferencesApi";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 用户偏好设置(M2-05,2026-09-20,方案 §9):
 * 首版三项**能落地**的结构化偏好——报告语言 / 默认成果目录 / 命名习惯。
 * 保存到后端 app_setting,每轮对话注入系统提示;localStorage 只作离线缓存。
 */
export function UserPreferencesSettings() {
  const [prefs, setPrefs] = useState<UserPreferences>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!USE_BACKEND) {
        setLoading(false);
        return;
      }
      try {
        const loaded = await userPreferencesApi.get();
        if (!cancelled) setPrefs(loaded);
      } catch {
        if (!cancelled) setLoadFailed(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const save = async () => {
    if (!USE_BACKEND) {
      toast.info("偏好保存需要连接后端服务");
      return;
    }
    setSaving(true);
    try {
      const saved = await userPreferencesApi.update(prefs);
      setPrefs(saved);
      toast.success("偏好已保存,将应用到新任务");
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="bg-card border border-border rounded-xl p-6 text-xs text-muted-foreground">加载偏好…</div>;
  }

  return (
    <div className="bg-card border border-border rounded-xl shadow-sm overflow-hidden">
      <div className="p-6 space-y-5">
        <div>
          <div className="text-sm font-bold text-foreground">我的偏好</div>
          <p className="text-xs text-muted-foreground mt-1">
            这三项会进入每轮对话的运行上下文,实际影响结果;不是只改界面的设置。
            当前要求与本次任务配置的优先级高于这些偏好。
          </p>
          {loadFailed && (
            <p className="text-[11px] text-red-500 dark:text-red-400 mt-2">偏好加载失败,请检查后端服务后刷新重试</p>
          )}
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">报告与成果默认语言</label>
          <Input
            className="h-9 text-sm"
            placeholder="例如：中文 / English"
            value={prefs.reportLanguage ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, reportLanguage: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">生成报告、整理结果时默认使用的语言。</p>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">默认成果目录（工作区相对路径）</label>
          <Input
            className="h-9 text-sm"
            placeholder="例如：reports"
            value={prefs.defaultOutputDir ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, defaultOutputDir: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">保存报告/导出文件时的默认落点(在「资料 → 工作区」可见)。</p>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">文件命名习惯</label>
          <Input
            className="h-9 text-sm"
            placeholder="例如：日期前缀 YYYYMMDD-名称"
            value={prefs.namingStyle ?? ""}
            onChange={(e) => setPrefs((p) => ({ ...p, namingStyle: e.target.value }))}
          />
          <p className="text-[11px] text-muted-foreground">生成文件名时遵循的习惯。</p>
        </div>

        <div className="flex justify-end">
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={save} disabled={saving}>
            <Save className="w-3.5 h-3.5 mr-1.5" /> {saving ? "保存中…" : "保存偏好"}
          </Button>
        </div>
      </div>
    </div>
  );
}
