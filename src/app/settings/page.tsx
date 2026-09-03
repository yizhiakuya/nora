'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { SettingsNav } from "@/components/settings/SettingsNav";
import { GeneralSettings } from "@/components/settings/GeneralSettings";
import { KnowledgeAISettings } from "@/components/settings/KnowledgeAISettings";
import { AccountSettings } from "@/components/settings/AccountSettings";
import { SecuritySettings } from "@/components/settings/SecuritySettings";
import { NotificationSettings } from "@/components/settings/NotificationSettings";
import { BillingSettings } from "@/components/settings/BillingSettings";

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState("通用");

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", isCurrent: false },
          { label: "设置中心", isCurrent: true },
        ]}
      />

      <div className="flex-1 flex overflow-hidden bg-background dark:bg-background">
        <SettingsNav activeTab={activeTab} onSelect={setActiveTab} />

        <div className="flex-1 overflow-y-auto p-4 sm:p-8 custom-scroll relative">
          <div className="max-w-3xl space-y-8 pb-20 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <div>
              <h1 className="text-2xl font-bold text-foreground mb-1">{activeTab}设置</h1>
              <p className="text-sm text-muted-foreground">管理您的账号、通知、订阅与本地安全偏好。</p>
            </div>

            {activeTab === "通用" && <GeneralSettings />}
            {activeTab === "知识库与 AI" && <KnowledgeAISettings />}
            {activeTab === "账号" && <AccountSettings />}
            {activeTab === "安全与 API" && <SecuritySettings />}
            {activeTab === "通知" && <NotificationSettings />}
            {activeTab === "订阅" && <BillingSettings />}
          </div>
        </div>
      </div>
    </>
  );
}
