import { create } from "zustand";
import { persist } from "zustand/middleware";
import { Braces } from "lucide-react";
import { Skill } from "@/types";
import { skillsApi, type BackendSkill } from "@/lib/services/skillsApi";
import { toast } from "sonner";

interface SkillsState {
  skills: Skill[];
  /** 后端模式:拉取服务端技能列表(不带正文) */
  syncFromBackend: () => Promise<void>;
  /** 后端模式:读取单个技能详情(含正文) */
  loadDetail: (id: number) => Promise<Skill | null>;
  addSkill: (skill: Skill) => void;
  updateSkill: (id: number, patch: Partial<Skill>) => void;
  removeSkill: (id: number) => void;
  toggleSkill: (id: number) => void;
}

/** 自定义技能的统一视觉样式。 */
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
    ...(s.instructions !== undefined ? { instructions: s.instructions } : {}),
  };
}

function restoreVisual(s: Skill | PersistedSkill): Skill {
  return {
    ...s,
    icon: CUSTOM_VISUAL.icon,
    color: CUSTOM_VISUAL.color,
    bg: CUSTOM_VISUAL.bg,
  } as Skill;
}

/** 后端行 → 前端 Skill(目录接口无正文,instructions 留空待详情加载)。 */
function fromBackend(row: BackendSkill): Skill {
  return restoreVisual({
    id: row.id,
    name: row.name,
    desc: row.description ?? "",
    category: row.category,
    enabled: row.enabled,
    isOfficial: false,
    createdAt: row.updatedAt ?? row.createdAt,
    ...(row.instructions != null ? { instructions: row.instructions } : {}),
  } as PersistedSkill);
}

/**
 * 技能唯一数据源：AI 能力页 / 设置中心技能页共享，启停状态全局一致。
 *
 * CRUD 走 /api/skills,本地仅作缓存与乐观更新;
 *
 * 持久化时剔除 icon/color/bg：Lucide 组件引用 JSON 序列化后会变成空对象，
 * 再水合渲染会报 Element type is invalid。水合时统一恢复为 Braces 视觉。
 */
export const useSkills = create<SkillsState>()(
  persist<SkillsState, [], [], PersistedSkillsState>(
    (set, get) => ({
      skills: [],
      syncFromBackend: async () => {
        try {
          const rows = await skillsApi.listSkills();
          set({ skills: rows.map(fromBackend) });
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      loadDetail: async (id) => {
        try {
          const row = await skillsApi.getSkill(id);
          const detailed = fromBackend(row);
          set((state) => ({
            skills: state.skills.map((s) => (s.id === id ? { ...s, instructions: detailed.instructions } : s)),
          }));
          return detailed;
        } catch (e) {
          toast.error(e instanceof Error ? e.message : "加载技能详情失败");
          return null;
        }
      },
      addSkill: (skill) => {
        skillsApi
          .createSkill({
            name: skill.name,
            description: skill.desc,
            instructions: skill.instructions ?? "",
            category: skill.category,
          })
          .then((saved) => {
            // 用服务端行替换乐观项(id/时间为准)
            set((state) => ({
              skills: state.skills.map((s) => (s.id === skill.id ? fromBackend(saved) : s)),
            }));
          })
          .catch((e) => {
            // 失败回滚乐观项,避免界面出现未持久化的幽灵技能
            set((state) => ({ skills: state.skills.filter((s) => s.id !== skill.id) }));
            toast.error(e instanceof Error ? e.message : "创建技能失败");
          });
        set((state) => ({ skills: [...state.skills, skill] }));
      },
      updateSkill: (id, patch) => {
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, ...patch } : s)) }));
        skillsApi
          .updateSkill(id, {
            ...(patch.name !== undefined ? { name: patch.name } : {}),
            ...(patch.desc !== undefined ? { description: patch.desc } : {}),
            ...(patch.instructions !== undefined ? { instructions: patch.instructions } : {}),
            ...(patch.category !== undefined ? { category: patch.category } : {}),
            ...(patch.enabled !== undefined ? { enabled: patch.enabled } : {}),
          })
          .catch((e) => {
            toast.error(e instanceof Error ? e.message : "更新技能失败");
            void get().syncFromBackend();
          });
      },
      removeSkill: (id) => {
        set((state) => ({ skills: state.skills.filter((s) => s.id !== id) }));
        skillsApi.deleteSkill(id).catch((e) => {
          toast.error(e instanceof Error ? e.message : "删除技能失败");
          void get().syncFromBackend();
        });
      },
      toggleSkill: (id) => {
        const next = get().skills.find((s) => s.id === id);
        if (!next) return;
        const enabled = !next.enabled;
        set((state) => ({ skills: state.skills.map((s) => (s.id === id ? { ...s, enabled } : s)) }));
        skillsApi.updateSkill(id, { enabled }).catch((e) => {
          toast.error(e instanceof Error ? e.message : "切换技能状态失败");
          void get().syncFromBackend();
        });
      },
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
