import { Switch } from "@/components/ui/switch";
import { Skill } from "@/types";

export function SkillCard({ skill, onToggle, onOpen }: { skill: Skill; onToggle: (id: number) => void; onOpen: (skill: Skill) => void }) {
  const Icon = skill.icon;
  return (
    <div className={`surface min-w-0 flex flex-col gap-4 p-5 ${skill.enabled ? "" : "opacity-70"}`}>
      <div className="flex justify-between items-center">
        <div className="w-9 h-9 rounded-lg bg-muted text-muted-foreground flex items-center justify-center">
          <Icon className="w-4 h-4" />
        </div>
        <Switch aria-label={`启用技能：${skill.name}`} checked={skill.enabled} onCheckedChange={() => onToggle(skill.id)} />
      </div>
      <button type="button" aria-label={`查看技能：${skill.name}`} onClick={() => onOpen(skill)} className="group min-w-0 flex-1 rounded-md text-left">
        <h3 className="text-sm font-semibold text-foreground group-hover:text-primary transition-colors">{skill.name}</h3>
        <p className="mt-2 text-xs text-muted-foreground leading-relaxed line-clamp-3">{skill.desc}</p>
      </button>
      <div className="flex items-center justify-between gap-2 pt-3 border-t border-border">
        <div className="min-w-0 flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <span className="shrink-0 rounded bg-muted px-1.5 py-0.5">{skill.isOfficial ? "内置" : "自定义"}</span>
          {!skill.isOfficial && skill.createdAt && <span className="truncate">{skill.createdAt}</span>}
        </div>
        <span className={`shrink-0 text-[11px] ${skill.enabled ? "text-green-600 dark:text-green-400" : "text-muted-foreground"}`}>{skill.enabled ? "可用" : "已停用"}</span>
      </div>
    </div>
  );
}
