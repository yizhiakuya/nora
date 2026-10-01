import { requestJson } from "@/lib/api/client";
/**
 * 用户偏好接入层(M2-05,方案 §9):
 * - get    → GET /api/user-preferences
 * - update → PUT /api/user-preferences(部分更新,白名单字段)
 *
 * 后端 app_setting 持久化;localStorage 只作缓存。偏好会注入每轮对话的
 * 系统提示(报告语言/默认成果目录/命名习惯),实际影响运行行为。
 */
export interface UserPreferences {
  /** 报告与成果默认语言(如 "中文" / "English") */
  reportLanguage?: string;
  /** 默认成果目录(工作区相对路径,如 "reports") */
  defaultOutputDir?: string;
  /** 文件命名习惯(自由文本,如 "日期前缀 YYYYMMDD" ) */
  namingStyle?: string;
}

export const userPreferencesApi = {
  async get(): Promise<UserPreferences> {
    return requestJson<UserPreferences>("/user-preferences");
  },
  async update(patch: UserPreferences): Promise<UserPreferences> {
    return requestJson<UserPreferences>("/user-preferences", {
      method: "PUT",
      body: JSON.stringify(patch),
    });
  },
};
