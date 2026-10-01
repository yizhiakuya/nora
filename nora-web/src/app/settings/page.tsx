'use client';

import { useState, useEffect } from "react";
import { useSearchParams } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { GeneralSettings } from "@/components/settings/GeneralSettings";
import { ModelSettings } from "@/components/settings/ModelSettings";
import { KnowledgeAISettings } from "@/components/settings/KnowledgeAISettings";
import { SecuritySettings } from "@/components/settings/SecuritySettings";
import { NotificationSettings } from "@/components/settings/NotificationSettings";
import { EnvVarsSettings } from "@/components/settings/EnvVarsSettings";
import { NetworkSettings } from "@/components/settings/NetworkSettings";
import { SkillsView } from "@/components/skills/SkillsView";
import { ConnectionsView } from "@/components/settings/ConnectionsView";
import { UserPreferencesSettings } from "@/components/settings/UserPreferencesSettings";

/**
 * 设置中心(M1-04,2026-09-20,方案 §4.4):
 * 分组:通用 / 模型 / 连接与工具(MCP)/ 技能 / 知识库与 AI / 通知 / 安全 /
 * 环境变量 / 网络(后三项为高级)。
 *
 * 深链:?section=<英文标识> 为稳定契约;兼容旧 ?tab=<中文值>
 * (模型管理/环境变量等)。两种参数都可刷新/返回/复制链接保持。
 */
const SECTIONS = [
  { key: "general", label: "通用", legacyTabs: ["通用"] },
  { key: "model", label: "模型", legacyTabs: ["模型管理"] },
  { key: "connections", label: "连接与工具", legacyTabs: ["连接与工具"] },
  { key: "skills", label: "技能", legacyTabs: [] },
  { key: "knowledge", label: "知识库与 AI", legacyTabs: ["知识库与 AI"] },
  { key: "notifications", label: "通知", legacyTabs: ["通知"] },
  { key: "security", label: "安全", legacyTabs: ["安全"] },
  { key: "env", label: "环境变量（高级）", legacyTabs: ["环境变量"] },
  { key: "network", label: "网络（高级）", legacyTabs: ["网络"] },
] as const;

type SectionKey = (typeof SECTIONS)[number]["key"];

function resolveSection(params: URLSearchParams): SectionKey {
  const section = params.get("section");
  if (section && SECTIONS.some((s) => s.key === section)) return section as SectionKey;
  const tab = params.get("tab");
  if (tab) {
    const hit = SECTIONS.find((s) => (s.legacyTabs as readonly string[]).includes(tab));
    if (hit) return hit.key;
  }
  return "general";
}

export default function SettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const section = resolveSection(searchParams);
  const [activeTab, setActiveTab] = useState<SectionKey>(section);

  // 路由参数变化同步状态(M1-04):不能只在首次 mount 读取 URL——
  // 从其他页跳转(如 /skills → 设置)时组件已挂载,必须跟随 URL 更新。
  useEffect(() => {
    setActiveTab(section);
  }, [section]);

  const switchSection = (key: SectionKey) => {
    setActiveTab(key);
    const params = new URLSearchParams(searchParams);
    params.delete("tab");
    params.set("section", key);
    setSearchParams(params, { replace: true });
  };

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "Nora", href: "/", isCurrent: false },
          { label: "设置", isCurrent: true },
        ]}
      />

      <div className="flex-1 flex flex-col overflow-hidden bg-background">
        <div className="flex-1 overflow-y-auto p-4 sm:p-8 custom-scroll relative">
          <div className="max-w-3xl mx-auto space-y-6 pb-20">

            {/* Header & Description */}
            <div>
              <h1 className="text-2xl font-bold text-foreground mb-1.5">设置</h1>
              <p className="text-sm text-muted-foreground">管理模型、连接与工具、技能与本地偏好。</p>
            </div>

            {/* Pill-style Tabs */}
            <div className="flex flex-wrap gap-2 pb-2">
              {SECTIONS.map((s) => (
                <button
                  key={s.key}
                  type="button"
                  role="tab"
                  aria-selected={activeTab === s.key}
                  onClick={() => switchSection(s.key)}
                  className={`px-3.5 py-1.5 text-sm font-medium rounded-lg transition-all ${
                    activeTab === s.key
                      ? "bg-white dark:bg-gray-800 text-blue-600 dark:text-blue-400 shadow-sm border border-gray-200/60 dark:border-gray-700"
                      : "text-gray-600 dark:text-gray-400 hover:bg-gray-200/50 dark:hover:bg-gray-800/50 hover:text-gray-900 dark:hover:text-gray-100 border border-transparent"
                  }`}
                >
                  {s.label}
                </button>
              ))}
            </div>

            <div className="animate-in fade-in slide-in-from-bottom-2 duration-300">
              {activeTab === "general" && (
                <div className="space-y-6">
                  <UserPreferencesSettings />
                  <GeneralSettings />
                </div>
              )}
              {activeTab === "model" && <ModelSettings />}
              {activeTab === "connections" && <ConnectionsView />}
              {activeTab === "skills" && <SkillsView />}
              {activeTab === "knowledge" && <KnowledgeAISettings />}
              {activeTab === "notifications" && <NotificationSettings />}
              {activeTab === "security" && <SecuritySettings />}
              {activeTab === "env" && <EnvVarsSettings />}
              {activeTab === "network" && <NetworkSettings />}
            </div>

          </div>
        </div>
      </div>
    </>
  );
}
