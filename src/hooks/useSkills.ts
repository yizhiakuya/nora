import { create } from "zustand";
import { persist } from "zustand/middleware";
import { Skill } from "@/types";
import { MOCK_SKILLS } from "@/lib/mockData";

interface SkillsState {
  skills: Skill[];
  addSkill: (skill: Skill) => void;
  updateSkill: (id: number, patch: Partial<Skill>) => void;
  removeSkill: (id: number) => void;
  toggleSkill: (id: number) => void;
}

/**
 * AI 能力唯一数据源：能力页共享，
 * 启停状态全局一致（决定 AI 在本工作台能做什么）。
 */
export const useSkills = create<SkillsState>()(
  persist(
    (set) => ({
      skills: MOCK_SKILLS,
      addSkill: (skill) => set((state) => ({ skills: [...state.skills, skill] })),
      updateSkill: (id, patch) =>
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, ...patch } : s)) })),
      removeSkill: (id) => set((state) => ({ skills: state.skills.filter((s) => s.id !== id) })),
      toggleSkill: (id) =>
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, enabled: !s.enabled } : s)) })),
    }),
    { name: "ai-skills" }
  )
);
