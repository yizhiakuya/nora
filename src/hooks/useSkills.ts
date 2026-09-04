import { create } from "zustand";
import { persist } from "zustand/middleware";
import { Braces } from "lucide-react";
import { Skill } from "@/types";
import { MOCK_SKILLS } from "@/lib/mockData";

interface SkillsState {
  skills: Skill[];
  addSkill: (skill: Skill) => void;
  updateSkill: (id: number, patch: Partial<Skill>) => void;
  removeSkill: (id: number) => void;
  toggleSkill: (id: number) => void;
}

/** 自定义技能的固定视觉样式（官方技能以 MOCK_SKILLS 为准）。 */
const CUSTOM_VISUAL = {
  icon: Braces,
  color: "text-blue-500 dark:text-blue-400",
  bg: "bg-blue-100 dark:bg-blue-900/50",
};

/** 持久化字段：剔除无法序列化的 Lucide 组件引用与纯展示样式。 */
type PersistedSkill = Omit<Skill, "icon" | "color" | "bg">;
type PersistedSkillsState = { skills: PersistedSkill[] };

function stripVisual(s: Skill): PersistedSkill {
  return {
    id: s.id,
    name: s.name,
    desc: s.desc,
    category: s.category,
    enabled: s.enabled,
    isOfficial: s.isOfficial,
    ...(s.createdAt ? { createdAt: s.createdAt } : {}),
    ...(s.schema ? { schema: s.schema } : {}),
    ...(s.authType ? { authType: s.authType } : {}),
  };
}

function restoreVisual(s: Skill | PersistedSkill): Skill {
  const seed = s.isOfficial ? MOCK_SKILLS.find((m) => m.id === s.id) : undefined;
  return {
    ...s,
    icon: seed?.icon ?? CUSTOM_VISUAL.icon,
    color: seed?.color ?? CUSTOM_VISUAL.color,
    bg: seed?.bg ?? CUSTOM_VISUAL.bg,
  } as Skill;
}

/**
 * AI 能力唯一数据源：能力页共享，
 * 启停状态全局一致（决定 AI 在本工作台能做什么）。
 *
 * 持久化时剔除 icon/color/bg：Lucide 组件引用 JSON 序列化后会变成空对象，
 * 再水合渲染会报 Element type is invalid。水合时按 id 恢复官方视觉，
 * 自定义技能统一恢复为 Braces 视觉。
 */
export const useSkills = create<SkillsState>()(
  persist<SkillsState, [], [], PersistedSkillsState>(
    (set) => ({
      skills: MOCK_SKILLS,
      addSkill: (skill) => set((state) => ({ skills: [...state.skills, skill] })),
      updateSkill: (id, patch) =>
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, ...patch } : s)) })),
      removeSkill: (id) => set((state) => ({ skills: state.skills.filter((s) => s.id !== id) })),
      toggleSkill: (id) =>
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, enabled: !s.enabled } : s)) })),
    }),
    {
      name: "ai-skills",
      partialize: (state): PersistedSkillsState => ({
        skills: state.skills.map(stripVisual),
      }),
      merge: (persisted, current) => {
        const p = (persisted ?? {}) as Partial<PersistedSkillsState>;
        const skills = (p.skills ?? current.skills).map(restoreVisual);
        return { ...current, skills };
      },
    }
  )
);
