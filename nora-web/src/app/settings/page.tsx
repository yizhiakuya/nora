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
import { NetworkSettings } from "@/components/settings/NetworkSettings";

const TABS = ["通用", "模型管理", "知识库与 AI", "环境变量", "网络", "账号", "安全", "通知"];

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState("通用");

  // 支持 /settings?tab=模型管理 深链（替代原独立页路由）
  useEffect(() => {
    const tab = new URLSearchParams(window.location.search).get("tab");
    const valid = ["通用", "模型管理", "知识库与 AI", "环境变量", "网络", "账号", "安全", "通知"];
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

      <div className="flex-1 flex flex-col overflow-hidden bg-background">
        <div className="flex-1 overflow-y-auto p-4 sm:p-8 custom-scroll relative">
          <div className="max-w-3xl mx-auto space-y-6 pb-20">

            {/* Header & Description */}
            <div>
              <h1 className="text-2xl font-bold text-foreground mb-1.5">设置中心</h1>
              <p className="text-sm text-muted-foreground">管理您的账号、模型接入、凭据与本地安全偏好。</p>
            </div>

            {/* Pill-style Tabs */}
            <div className="flex flex-wrap gap-2 pb-2">
              {TABS.map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  aria-selected={activeTab === t}
                  onClick={() => setActiveTab(t)}
                  className={`px-3.5 py-1.5 text-sm font-medium rounded-lg transition-all ${
                    activeTab === t
                      ? "bg-white dark:bg-gray-800 text-blue-600 dark:text-blue-400 shadow-sm border border-gray-200/60 dark:border-gray-700"
                      : "text-gray-600 dark:text-gray-400 hover:bg-gray-200/50 dark:hover:bg-gray-800/50 hover:text-gray-900 dark:hover:text-gray-100 border border-transparent"
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>

            <div className="animate-in fade-in slide-in-from-bottom-2 duration-300">
              {activeTab === "通用" && <GeneralSettings />}
              {activeTab === "模型管理" && <ModelSettings />}
              {activeTab === "知识库与 AI" && <KnowledgeAISettings />}
              {activeTab === "环境变量" && <EnvVarsSettings />}
              {activeTab === "网络" && <NetworkSettings />}
              {activeTab === "账号" && <AccountSettings />}
              {activeTab === "安全" && <SecuritySettings />}
              {activeTab === "通知" && <NotificationSettings />}
            </div>

          </div>
        </div>
      </div>
    </>
  );
}