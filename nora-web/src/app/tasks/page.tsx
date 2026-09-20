'use client';

import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { ListCheck, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AutomationList } from "@/components/automations/AutomationList";
import { ExecutionHistory } from "@/components/automations/ExecutionHistory";
import { NewAutomationModal } from "@/components/automations/NewAutomationModal";
import { RunningTasks } from "@/components/automations/RunningTasks";
import { useAutomations } from "@/hooks/useAutomations";

/**
 * 任务页(M1,2026-09-20,按产品改造方案 §4.3):
 * 「定期任务 / 执行记录 / 正在处理」三视图聚合。规则启用状态与一次运行
 * 的状态分开展示(审查报告 B08)。
 *
 * 路由:/tasks?view=running|schedules|history(默认 schedules);
 * /automations 兼容跳转到这里(view 保留)。
 */
const VIEWS = [
  { key: "running", label: "正在处理" },
  { key: "schedules", label: "定期任务" },
  { key: "history", label: "执行记录" },
] as const;

type TaskView = (typeof VIEWS)[number]["key"];

export default function TasksPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const viewParam = searchParams.get("view");
  const view: TaskView = (VIEWS.some((v) => v.key === viewParam) ? viewParam : "schedules") as TaskView;
  const [modalOpen, setModalOpen] = useState(false);
  /** M4-02:从成果创建定期任务时的预填(名称+指令)。 */
  const [modalPrefill, setModalPrefill] = useState<{ name?: string; action?: string } | null>(null);
  const syncFromBackend = useAutomations((s) => s.syncFromBackend);

  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

  // 视图切换写入 URL(刷新/返回键/复制链接都保持一致)
  const setView = (next: TaskView) => {
    const params = new URLSearchParams(searchParams);
    params.set("view", next);
    setSearchParams(params, { replace: true });
  };

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "任务", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => setModalOpen(true)}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建定期任务
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <ListCheck className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> 任务
            </h1>
            <p className="text-xs text-muted-foreground mt-1">
              让 AI 定期处理重复性工作,并查看每次执行的完整结果。
            </p>
            <div role="tablist" aria-label="任务视图" className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit">
              {VIEWS.map((v) => (
                <button
                  key={v.key}
                  type="button"
                  role="tab"
                  aria-selected={view === v.key}
                  onClick={() => setView(v.key)}
                  className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${view === v.key ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                >
                  {v.label}
                </button>
              ))}
            </div>
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            {view === "running" && <RunningTasks />}
            {view === "schedules" && <AutomationList />}
            {view === "history" && (
              <ExecutionHistory
                onCreateSchedule={(prefill) => {
                  setModalPrefill(prefill);
                  setView("schedules");
                  setModalOpen(true);
                }}
              />
            )}
          </div>
        </div>
      </div>

      <NewAutomationModal
        isOpen={modalOpen}
        onClose={() => { setModalOpen(false); setModalPrefill(null); }}
        prefill={modalPrefill}
      />
    </>
  );
}
