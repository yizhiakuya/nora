import { create } from "zustand";
import { persist } from "zustand/middleware";

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
  /** 上传后自动入库(真实消费:files 页上传完成回调)。 */
  autoIndex: boolean;
}

interface PreferencesState {
  account: AccountPrefs;
  notifications: NotificationPrefs;
  knowledgeAI: KnowledgeAIPrefs;
  setAccount: (patch: Partial<AccountPrefs>) => void;
  setNotifications: (patch: Partial<NotificationPrefs>) => void;
  setKnowledgeAI: (patch: Partial<KnowledgeAIPrefs>) => void;
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
  autoIndex: true,
};

/**
 * 设置中心的持久化偏好（账号 / 通知 / 知识库与 AI）。
 * 注:环境变量(2026-09-19)已移至服务端 app_setting(跨浏览器一致);
 * 清洗规则随死功能 tab 一并移除(后端无清洗逻辑)。
 * 统一收口，替代各组件内的 useState「假保存」：刷新后仍保留。
 */
export const usePreferences = create<PreferencesState>()(
  persist(
    (set) => ({
      account: DEFAULT_ACCOUNT,
      notifications: DEFAULT_NOTIFICATIONS,
      knowledgeAI: DEFAULT_KNOWLEDGE_AI,
      setAccount: (patch) => set((s) => ({ account: { ...s.account, ...patch } })),
      setNotifications: (patch) =>
        set((s) => ({ notifications: { ...s.notifications, ...patch } })),
      setKnowledgeAI: (patch) =>
        set((s) => ({ knowledgeAI: { ...s.knowledgeAI, ...patch } })),
    }),
    { name: "user-preferences" }
  )
);
