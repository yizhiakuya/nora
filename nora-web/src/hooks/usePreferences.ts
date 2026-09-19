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
  // 空身份(2026-09-19 去假数据):此前默认写死 "Nora Clark"/
  // "nora.clark@example.com"——从没设置过的用户也顶着假名字与占位邮箱;
  // 现在为空,UI 显示通用称谓("我"),用户可在设置里填写真实信息
  name: "",
  email: "",
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
    {
      name: "user-preferences",
      version: 1,
      // v0 默认值是写死的假身份(Nora Clark/example.com),用户没改过则清空;
      // 用户自己填过的(与假默认不同)保留
      migrate: (state) => {
        const s = state as { account?: { name?: string; email?: string } };
        if (s.account && s.account.name === "Nora Clark" && s.account.email === "nora.clark@example.com") {
          s.account.name = "";
          s.account.email = "";
        }
        return s as never;
      },
    }
  )
);
