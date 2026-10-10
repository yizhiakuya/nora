import { Navigate, useSearchParams } from "react-router-dom";
import { ConfigurationLayout } from "@/components/settings/ConfigurationLayout";
import { GeneralSettings } from "@/components/settings/GeneralSettings";
import { KnowledgeAISettings } from "@/components/settings/KnowledgeAISettings";
import { SecuritySettings } from "@/components/settings/SecuritySettings";
import { NotificationSettings } from "@/components/settings/NotificationSettings";

// 旧设置书签迁移到唯一的 Agent 配置入口。
const AGENT_SECTIONS: Record<string, string> = {
  model: "model", 模型管理: "model", connections: "connections", 连接与工具: "connections",
  skills: "skills", env: "env", 环境变量: "env", network: "network", 网络: "network",
};
const SECTIONS = [
  { key: "general", label: "通用", legacy: "通用" },
  { key: "knowledge", label: "资料与索引", legacy: "知识库与 AI" },
  { key: "notifications", label: "通知", legacy: "通知" },
  { key: "security", label: "隐私", legacy: "安全" },
].map(section => ({ ...section, href: `/settings?section=${section.key}` }));

export default function SettingsPage() {
  const [params] = useSearchParams();
  const requested = params.get("section") ?? params.get("tab") ?? "general";
  const agentSection = AGENT_SECTIONS[requested];
  if (agentSection) {
    const migrated = new URLSearchParams(params);
    migrated.set("section", agentSection);
    if (!params.has("section")) migrated.delete("tab");
    return <Navigate to={`/agent?${migrated}`} replace />;
  }
  const active = SECTIONS.find(section => section.key === requested || section.legacy === requested)?.key ?? "general";
  return <ConfigurationLayout title="设置" description="管理工作台外观、资料索引、通知与隐私。" sections={SECTIONS} active={active}>
    {active === "general" && <GeneralSettings />}
    {active === "knowledge" && <KnowledgeAISettings />}
    {active === "notifications" && <NotificationSettings />}
    {active === "security" && <SecuritySettings />}
  </ConfigurationLayout>;
}
