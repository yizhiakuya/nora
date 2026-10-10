import { useSearchParams } from "react-router-dom";
import { ConfigurationLayout } from "@/components/settings/ConfigurationLayout";
import { AgentBehaviorSettings } from "@/components/agent/AgentBehaviorSettings";
import { AgentMemorySettings } from "@/components/agent/AgentMemorySettings";
import { ModelSettings } from "@/components/settings/ModelSettings";
import { UserPreferencesSettings } from "@/components/settings/UserPreferencesSettings";
import { ConnectionsView } from "@/components/settings/ConnectionsView";
import { EnvVarsSettings } from "@/components/settings/EnvVarsSettings";
import { NetworkSettings } from "@/components/settings/NetworkSettings";
import { SkillsView } from "@/components/skills/SkillsView";

const SECTIONS = [
  { key: "general", label: "基本行为" },
  { key: "model", label: "模型" },
  { key: "memory", label: "指令与记忆" },
  { key: "skills", label: "技能" },
  { key: "connections", label: "连接与工具" },
  { key: "env", label: "环境变量" },
  { key: "network", label: "网络" },
].map(section => ({ ...section, href: `/agent?section=${section.key}` }));

export default function AgentPage() {
  const [params] = useSearchParams();
  const active = SECTIONS.find(section => section.key === params.get("section"))?.key ?? "general";
  return <ConfigurationLayout title="Agent" description="配置 Nora 如何思考、执行任务，以及记住什么。" sections={SECTIONS} active={active}>
    {active === "general" && <><AgentBehaviorSettings /><UserPreferencesSettings /></>}
    {active === "model" && <ModelSettings />}
    {active === "memory" && <AgentMemorySettings />}
    {active === "skills" && <SkillsView />}
    {active === "connections" && <ConnectionsView />}
    {active === "env" && <EnvVarsSettings />}
    {active === "network" && <NetworkSettings />}
  </ConfigurationLayout>;
}
