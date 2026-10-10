'use client';

import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { ListCheck, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
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
        breadcrumbs={[{ label: "Nora", href: "/", isCurrent: false }, { label: "任务", isCurrent: true }]}
        actions={
          <Button size="sm"  onClick={() => setModalOpen(true)}>
            <Plus className="w-4 h-4" /> 新建定期任务
          </Button>
        }
      />

      <div className="page-content custom-scroll">
        <div className="page-container">
          <div className="animate-in fade-in duration-200">
            <h1 className="page-heading flex items-center gap-2.5">
              <ListCheck className="w-5 h-5 text-muted-foreground" /> 任务
            </h1>
            <p className="page-description">
              让 AI 定期处理重复性工作,并查看每次执行的完整结果。
            </p>
          </div>
          <Tabs value={view} onValueChange={value => setView(value as TaskView)}>
            <TabsList aria-label="任务视图">
              {VIEWS.map((v) => (
                <TabsTrigger key={v.key} value={v.key}>{v.label}</TabsTrigger>
              ))}
            </TabsList>
          <TabsContent value={view} className="mt-6">
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
          </TabsContent>
          </Tabs>
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
