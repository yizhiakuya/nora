'use client';

import { useState, useEffect } from "react";
import { Header } from "@/components/layout/Header";
import { GeneralSettings } from "@/components/settings/GeneralSettings";
import { ModelSettings } from "@/components/settings/ModelSettings";
import { KnowledgeAISettings } from "@/components/settings/KnowledgeAISettings";
import { AccountSettings } from "@/components/settings/AccountSettings";
import { SecuritySettings } from "@/components/settings/SecuritySettings";
import { NotificationSettings } from "@/components/settings/NotificationSettings";
import { EnvVarsSettings } from "@/components/settings/EnvVarsSettings";

const TABS = ["通用", "模型管理", "知识库与 AI", "环境变量", "账号", "安全", "通知"];

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState("通用");

  // 支持 /settings?tab=模型管理 深链（替代原独立页路由）
  useEffect(() => {
    const tab = new URLSearchParams(window.location.search).get("tab");
    const valid = ["通用", "模型管理", "知识库与 AI", "环境变量", "账号", "安全", "通知"];
    if (tab && valid.includes(tab)) setActiveTab(tab);
  }, []);

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", isCurrent: false },
          { label: "设置中心", isCurrent: true },
        ]}
      />

      <div className="flex-1 flex flex-col overflow-hidden bg-background dark:bg-background">

        {/* 顶部横向 Tab 切换（紧凑，不占侧栏） */}
        <div className="shrink-0 border-b border-border bg-card dark:bg-card px-4 sm:px-8 pt-3">
          <div className="max-w-3xl mx-auto flex gap-1 overflow-x-auto no-scrollbar" role="tablist" aria-label="设置分类">
            {TABS.map((t) => (
              <button
                key={t}
                type="button"
                role="tab"
                aria-selected={activeTab === t}
                onClick={() => setActiveTab(t)}
                className={`px-4 py-2 text-xs font-medium rounded-t-lg cursor-pointer transition-colors whitespace-nowrap border-b-2 -mb-px ${activeTab === t ? "border-primary text-primary bg-primary/5" : "border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/40"}`}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 sm:p-8 custom-scroll relative">
          <div className="max-w-3xl space-y-8 pb-20 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <div>
              <h1 className="text-2xl font-bold text-foreground mb-1">{activeTab}设置</h1>
              <p className="text-sm text-muted-foreground">管理您的账号、模型接入、凭据与本地安全偏好。</p>
            </div>

            {activeTab === "通用" && <GeneralSettings />}
            {activeTab === "模型管理" && <ModelSettings />}
            {activeTab === "知识库与 AI" && <KnowledgeAISettings />}
            {activeTab === "环境变量" && <EnvVarsSettings />}
            {activeTab === "账号" && <AccountSettings />}
            {activeTab === "安全" && <SecuritySettings />}
            {activeTab === "通知" && <NotificationSettings />}
          </div>
        </div>
      </div>
    </>
  );
}
