import { useState } from "react";
import { useRouter } from "next/navigation";
import { ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import { useSkills } from "@/hooks/useSkills";
import { toast } from "sonner";

interface AgentSkillsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

/**
 * 智能体技能批量启停：勾选 = 启用（与技能中心共用同一 store）。
 */
export function AgentSkillsModal({ isOpen, onClose }: AgentSkillsModalProps) {
  const router = useRouter();
  const { skills, toggleSkill } = useSkills();
  const [pending, setPending] = useState<Record<number, boolean>>({});

  const isEnabled = (id: number, enabled: boolean) => pending[id] ?? enabled;
  const changedCount = Object.keys(pending).length;

  const handleSave = () => {
    Object.entries(pending).forEach(([id, next]) => {
      const skill = skills.find((s) => s.id === Number(id));
      if (skill && skill.enabled !== next) toggleSkill(Number(id));
    });
    toast.success(`已更新 ${changedCount} 项技能配置`);
    setPending({});
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => { setPending({}); onClose(); }}
      title="配置智能体技能"
      width="w-[94%] sm:w-[480px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={() => { setPending({}); onClose(); }}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSave} disabled={changedCount === 0}>
            保存{changedCount > 0 ? `（${changedCount} 项变更）` : ""}
          </Button>
        </>
      }
    >
      <div className="space-y-2 max-h-[50vh] overflow-y-auto custom-scroll">
        {skills.map((skill) => {
          const Icon = skill.icon;
          const checked = isEnabled(skill.id, skill.enabled);
          return (
            <label
              key={skill.id}
              className={`flex items-center gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${checked ? "border-blue-200 dark:border-blue-800 bg-blue-50/50 dark:bg-blue-950/30" : "border-gray-200 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800"}`}
            >
              <input
                type="checkbox"
                className="w-4 h-4 rounded border-gray-300 dark:border-gray-700 text-blue-600 dark:text-blue-400 focus:ring-blue-500 cursor-pointer"
                checked={checked}
                onChange={() => setPending((prev) => ({ ...prev, [skill.id]: !checked }))}
              />
              <div className={`w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ${skill.bg} ${skill.color}`}>
                <Icon className="w-4 h-4" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-gray-800 dark:text-gray-100 truncate">{skill.name}</div>
                <div className="text-[10px] text-gray-500 dark:text-gray-400 truncate">{skill.desc}</div>
              </div>
              <span className={`text-[10px] px-2 py-0.5 rounded font-medium shrink-0 ${skill.isOfficial ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400" : "bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300"}`}>
                {skill.isOfficial ? "官方" : "自定义"}
              </span>
            </label>
          );
        })}
      </div>
      <button
        className="mt-4 text-xs text-blue-600 dark:text-blue-400 hover:underline flex items-center gap-1"
        onClick={() => { router.push("/skills"); }}
      >
        <ExternalLink className="w-3 h-3" /> 去技能中心创建自定义技能
      </button>
    </Modal>
  );
}
