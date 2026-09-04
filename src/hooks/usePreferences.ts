import { create } from "zustand";
import { persist } from "zustand/middleware";
import { EnvVar, MOCK_ENV_VARS } from "@/lib/devData";
import { MOCK_RULES } from "@/lib/knowledgeData";
import { PipelineRule } from "@/types";

export interface AccountPrefs {
  name: string;
  email: string;
  timezone: string;
  lang: string;
}

export interface NotificationPrefs {
  events: Record<string, boolean>;
  inApp: boolean;
  browser: boolean;
}

export interface KnowledgeAIPrefs {
  embedding: string;
  chunkSize: string;
  autoIndex: boolean;
}

interface PreferencesState {
  account: AccountPrefs;
  notifications: NotificationPrefs;
  knowledgeAI: KnowledgeAIPrefs;
  envVars: EnvVar[];
  cleaningRules: PipelineRule[];
  setAccount: (patch: Partial<AccountPrefs>) => void;
  setNotifications: (patch: Partial<NotificationPrefs>) => void;
  setKnowledgeAI: (patch: Partial<KnowledgeAIPrefs>) => void;
  addEnvVar: (v: EnvVar) => void;
  removeEnvVar: (key: string) => void;
  toggleRule: (id: number) => void;
}

const DEFAULT_ACCOUNT: AccountPrefs = {
  name: "Nora Clark",
  email: "nora.clark@example.com",
  timezone: "Asia/Shanghai (UTC+8)",
  lang: "简体中文",
};

const DEFAULT_NOTIFICATIONS: NotificationPrefs = {
  events: { taskDone: true, taskFail: true, indexed: true, svcError: true },
  inApp: true,
  browser: false,
};

const DEFAULT_KNOWLEDGE_AI: KnowledgeAIPrefs = {
  embedding: "text-embedding-3-small (1536维)",
  chunkSize: "512 token（推荐）",
  autoIndex: true,
};

/**
 * 设置中心的持久化偏好（账号 / 通知 / 知识库与 AI / 环境变量 / 清洗规则）。
 * 统一收口，替代各组件内的 useState「假保存」：刷新后仍保留。
 */
export const usePreferences = create<PreferencesState>()(
  persist(
    (set) => ({
      account: DEFAULT_ACCOUNT,
      notifications: DEFAULT_NOTIFICATIONS,
      knowledgeAI: DEFAULT_KNOWLEDGE_AI,
      envVars: MOCK_ENV_VARS,
      cleaningRules: MOCK_RULES,
      setAccount: (patch) => set((s) => ({ account: { ...s.account, ...patch } })),
      setNotifications: (patch) =>
        set((s) => ({ notifications: { ...s.notifications, ...patch } })),
      setKnowledgeAI: (patch) =>
        set((s) => ({ knowledgeAI: { ...s.knowledgeAI, ...patch } })),
      addEnvVar: (v) => set((s) => ({ envVars: [...s.envVars, v] })),
      removeEnvVar: (key) =>
        set((s) => ({ envVars: s.envVars.filter((e) => e.key !== key) })),
      toggleRule: (id) =>
        set((s) => ({
          cleaningRules: s.cleaningRules.map((r) =>
            r.id === id ? { ...r, enabled: !r.enabled } : r
          ),
        })),
    }),
    { name: "user-preferences" }
  )
);
