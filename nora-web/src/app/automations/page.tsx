'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Zap, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AutomationList } from "@/components/automations/AutomationList";
import { ExecutionHistory } from "@/components/automations/ExecutionHistory";
import { NewAutomationModal } from "@/components/automations/NewAutomationModal";
import { useAutomations } from "@/hooks/useAutomations";

const TABS = ["规则", "执行历史"] as const;

export default function AutomationsPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("规则");
  const [modalOpen, setModalOpen] = useState(false);
  const syncFromBackend = useAutomations((s) => s.syncFromBackend);

  // 后端模式:进入页面拉一次服务端规则与执行历史
  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

  return (
    <>
      <Header
        breadcrumbs={[{ label: "工作台", href: "/", isCurrent: false }, { label: "自动任务", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => setModalOpen(true)}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建任务
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="space-y-4 animate-in fade-in slide-in-from-top-4">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Zap className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> 自动任务
            </h1>
            <p className="text-xs text-muted-foreground mt-1">
              设置触发条件和执行动作，让 AI 自动处理重复性工作。
            </p>
            <div role="tablist" aria-label="自动任务视图" className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit">
              {TABS.map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  aria-selected={tab === t}
                  onClick={() => setTab(t)}
                  className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${tab === t ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
            {tab === "规则" && <AutomationList />}
            {tab === "执行历史" && <ExecutionHistory />}
          </div>
        </div>
      </div>

      <NewAutomationModal isOpen={modalOpen} onClose={() => setModalOpen(false)} />
    </>
  );
}
