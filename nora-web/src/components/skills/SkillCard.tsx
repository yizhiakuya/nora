import { Switch } from "@/components/ui/switch";
import { Skill } from "@/types";

export function SkillCard({ skill, onToggle, onOpen }: { skill: Skill; onToggle: (id: number) => void; onOpen: (skill: Skill) => void }) {
  const Icon = skill.icon;
  return (
    <div
      className={`bg-card border ${skill.enabled ? "border-border shadow-sm" : "border-border opacity-70"} rounded-xl p-5 hover:shadow-md transition-all relative flex flex-col h-[180px] cursor-pointer group`}
      title="查看技能详情"
      onClick={() => onOpen(skill)}
    >
      <div className="flex justify-between items-start mb-3">
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${skill.bg} ${skill.color}`}>
          <Icon className="w-5 h-5" />
        </div>
        <Switch checked={skill.enabled} onCheckedChange={() => onToggle(skill.id)} />
      </div>
      <h3 className="text-sm font-bold text-foreground mb-1.5 group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">{skill.name}</h3>
      <p className="text-xs text-muted-foreground leading-relaxed flex-1 overflow-hidden line-clamp-3">{skill.desc}</p>

      <div className="flex items-center justify-between mt-3 pt-3 border-t border-border">
        <div className="flex items-center gap-1.5 min-w-0">
          <span className={`text-[10px] px-2 py-0.5 rounded font-medium ${skill.isOfficial ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400" : "bg-muted text-muted-foreground"}`}>
            {skill.isOfficial ? "官方" : "自定义"}
          </span>
          {!skill.isOfficial && skill.createdAt && (
            <span className="text-[9px] text-muted-foreground/60 truncate">{skill.createdAt}</span>
          )}
        </div>
        {!skill.enabled && <span className="text-[10px] text-muted-foreground">已停用</span>}
        {skill.enabled && <span className="text-[10px] text-green-500 dark:text-green-400 font-medium">可用</span>}
      </div>
    </div>
  );
}
