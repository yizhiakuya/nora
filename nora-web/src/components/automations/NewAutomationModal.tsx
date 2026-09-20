'use client';

import { useState, useEffect } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useAutomations } from "@/hooks/useAutomations";
import { automationsApi, type ScheduleSpec } from "@/lib/services/automationsApi";
import { USE_BACKEND } from "@/lib/api/client";

const TRIGGER_OPTIONS = [
  { value: "manual", label: "手动触发" },
  { value: "daily", label: "每日定时" },
  { value: "weekly", label: "每周定时" },
  // 未接通的事件触发(2026-09-20 按审查报告标注):后端 create 接受 file/error,
  // 但调度只扫描 daily/weekly——没有事件驱动执行。开放选项会让用户建出
  // 「看似有效但永不会自动运行」的规则;标为不可选,接通真实事件后再开放。
  { value: "file", label: "文件上传时", disabled: true, hint: "即将支持" },
  { value: "error", label: "服务异常时", disabled: true, hint: "即将支持" },
] as const;

const WEEKDAYS = [
  { value: 1, label: "周一" },
  { value: 2, label: "周二" },
  { value: 3, label: "周三" },
  { value: 4, label: "周四" },
  { value: 5, label: "周五" },
  { value: 6, label: "周六" },
  { value: 7, label: "周日" },
];

interface NewAutomationModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** 预填(M4-02:从成果创建定期任务,带入名称与指令)。 */
  prefill?: { name?: string; action?: string } | null;
}

/** 本地时区(IANA);预览与保存都显式带时区,不依赖服务器默认。 */
function localTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai";
  } catch {
    return "Asia/Shanghai";
  }
}

export function NewAutomationModal({ isOpen, onClose, prefill }: NewAutomationModalProps) {
  const addRule = useAutomations((s) => s.addRule);
  const [name, setName] = useState("");
  const [trigger, setTrigger] = useState<string>("manual");
  const [action, setAction] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 日程字段(M4-01):时间/星期/时区——daily/weekly 必填
  const [localTime, setLocalTime] = useState("09:00");
  const [dayOfWeek, setDayOfWeek] = useState<number>(5);
  const [timezone, setTimezone] = useState<string>(localTimeZone());
  // 服务端计算的未来计划点(保存前预览,方案 §8.1)
  const [preview, setPreview] = useState<string[] | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      // M4-02:从成果创建时带入名称与指令(用户仍可编辑)
      setName(prefill?.name ?? "");
      setTrigger("manual");
      setAction(prefill?.action ?? "");
      setError(null);
      setSubmitting(false);
      setPreview(null);
      setPreviewError(null);
      setTimezone(localTimeZone());
    }
  }, [isOpen, prefill]);

  const needsSchedule = trigger === "daily" || trigger === "weekly";

  const buildSchedule = (): ScheduleSpec | null => {
    if (!needsSchedule) return null;
    return {
      frequency: trigger as "daily" | "weekly",
      localTime,
      ...(trigger === "weekly" ? { dayOfWeek } : {}),
      timezone,
    };
  };

  /** 预览下一次执行(服务端计算;失败显示原因,不编造时间)。 */
  const handlePreview = async () => {
    const schedule = buildSchedule();
    if (!schedule) return;
    if (!USE_BACKEND) {
      setPreviewError("预览需要连接后端服务");
      return;
    }
    setPreviewError(null);
    try {
      const times = await automationsApi.previewSchedule(schedule, trigger);
      setPreview(times);
    } catch (e) {
      setPreview(null);
      setPreviewError((e as Error).message);
    }
  };

  const handleSubmit = async () => {
    if (!name.trim()) {
      setError("任务名称不能为空");
      return;
    }
    if (!action.trim()) {
      setError("执行动作不能为空");
      return;
    }
    const schedule = buildSchedule();
    if (needsSchedule && !schedule) {
      setError("请填写完整的日程(时间/时区)");
      return;
    }
    setSubmitting(true);
    const triggerLabel = needsSchedule
      ? (trigger === "weekly"
          ? `每周${WEEKDAYS.find((w) => w.value === dayOfWeek)?.label ?? ""} ${localTime}`
          : `每日 ${localTime}`)
      : TRIGGER_OPTIONS.find((t) => t.value === trigger)?.label ?? "手动触发";
    const saved = await addRule(name.trim(), triggerLabel, action.trim(), schedule ?? undefined);
    setSubmitting(false);
    if (!saved) {
      // 后端失败:留在弹窗里让用户改后重试,不关窗、不报"创建成功"(2026-09-19 修假成功)
      setError("创建失败,请检查后端服务后重试");
      return;
    }
    toast.success(`自动任务「${name.trim()}」已创建`);
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="新建定期任务"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={submitting}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSubmit} disabled={submitting}>
            {submitting ? "创建中…" : "创建任务"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">任务名称</label>
          <Input
            placeholder="例如：每日巡检报告生成"
            className="h-9 text-sm"
            value={name}
            onChange={(e) => { setName(e.target.value); setError(null); }}
          />
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">触发条件</label>
          <Select value={trigger} onValueChange={(v) => { setTrigger(v); setPreview(null); setPreviewError(null); }}>
            <SelectTrigger className="w-full">
              <SelectValue placeholder="选择触发方式" />
            </SelectTrigger>
            <SelectContent>
              {TRIGGER_OPTIONS.map((t) => (
                <SelectItem key={t.value} value={t.value} disabled={"disabled" in t && t.disabled}>
                  {"hint" in t && t.hint ? `${t.label}（${t.hint}）` : t.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* 日程(M4-01):daily/weekly 显示时间/星期/时区,保存前可预览 */}
        {needsSchedule && (
          <div className="space-y-3 p-3 rounded-lg bg-muted/40 border border-border">
            <div className="grid grid-cols-2 gap-3">
              {trigger === "weekly" && (
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-foreground">星期</label>
                  <Select value={String(dayOfWeek)} onValueChange={(v) => { setDayOfWeek(Number(v)); setPreview(null); }}>
                    <SelectTrigger className="w-full h-9">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {WEEKDAYS.map((w) => (
                        <SelectItem key={w.value} value={String(w.value)}>{w.label}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-foreground">时间</label>
                <Input
                  type="time"
                  className="h-9 text-sm"
                  value={localTime}
                  onChange={(e) => { setLocalTime(e.target.value); setPreview(null); }}
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">时区</label>
              <Input
                className="h-9 text-sm font-mono"
                value={timezone}
                onChange={(e) => { setTimezone(e.target.value); setPreview(null); }}
                placeholder="Asia/Shanghai"
              />
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handlePreview}>
                预览执行时间
              </Button>
              {preview && (
                <span className="text-[11px] text-muted-foreground">
                  未来三次:{preview.map((t) => t.replace("T", " ").slice(0, 16)).join("、")}
                </span>
              )}
            </div>
            {previewError && <p className="text-[11px] text-red-500 dark:text-red-400">{previewError}</p>}
            <p className="text-[10px] text-muted-foreground/70">
              到点后 10 分钟内执行;超过宽限不自动补跑,会记录「已错过」并等你手动运行。
            </p>
          </div>
        )}

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">执行动作</label>
          <textarea
            rows={3}
            placeholder="描述 AI 要执行的操作，如：运行巡检查询并生成 Markdown 报告"
            className="w-full bg-muted border border-border rounded-lg p-3 text-sm focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-500 resize-none"
            value={action}
            onChange={(e) => { setAction(e.target.value); setError(null); }}
          />
        </div>

        {error && <p className="text-[11px] text-red-500 dark:text-red-400">{error}</p>}
      </div>
    </Modal>
  );
}
