'use client';

import { useState, useEffect } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useAutomations } from "@/hooks/useAutomations";

const TRIGGER_OPTIONS = [
  { value: "manual", label: "手动触发" },
  { value: "daily", label: "每日定时" },
  { value: "weekly", label: "每周定时" },
  { value: "file", label: "文件上传时" },
  { value: "error", label: "服务异常时" },
] as const;

interface NewAutomationModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export function NewAutomationModal({ isOpen, onClose }: NewAutomationModalProps) {
  const addRule = useAutomations((s) => s.addRule);
  const [name, setName] = useState("");
  const [trigger, setTrigger] = useState<string>("manual");
  const [action, setAction] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setName("");
      setTrigger("manual");
      setAction("");
      setError(null);
    }
  }, [isOpen]);

  const handleSubmit = () => {
    if (!name.trim()) {
      setError("任务名称不能为空");
      return;
    }
    if (!action.trim()) {
      setError("执行动作不能为空");
      return;
    }
    const triggerLabel = TRIGGER_OPTIONS.find((t) => t.value === trigger)?.label ?? "手动触发";
    addRule(name.trim(), triggerLabel, action.trim());
    toast.success(`自动任务「${name.trim()}」已创建`);
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="新建自动任务"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSubmit}>
            创建任务
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
          <Select value={trigger} onValueChange={setTrigger}>
            <SelectTrigger className="w-full">
              <SelectValue placeholder="选择触发方式" />
            </SelectTrigger>
            <SelectContent>
              {TRIGGER_OPTIONS.map((t) => (
                <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

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
