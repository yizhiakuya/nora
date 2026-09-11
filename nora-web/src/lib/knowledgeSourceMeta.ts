import { FileCode, Server, Database, File, MessageSquare } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { KnowledgeSource } from "@/types";

/** 知识来源的视觉元数据(图标/文案/配色),DocumentLibrary 与 RetrievalTest 共用。 */
export const SOURCE_META: Record<KnowledgeSource, { label: string; icon: LucideIcon; color: string; bg: string }> = {
  file:        { label: "文件上传", icon: File,          color: "text-blue-600 dark:text-blue-400",   bg: "bg-blue-100 dark:bg-blue-900/50" },
  database:    { label: "数据库",   icon: Database,      color: "text-orange-600 dark:text-orange-400", bg: "bg-orange-100 dark:bg-orange-900/50" },
  repo:        { label: "代码仓库", icon: FileCode,      color: "text-purple-600 dark:text-purple-400", bg: "bg-purple-100 dark:bg-purple-900/50" },
  environment: { label: "环境配置", icon: Server,        color: "text-green-600 dark:text-green-400",   bg: "bg-green-100 dark:bg-green-900/50" },
  chat:        { label: "对话产出", icon: MessageSquare,  color: "text-teal-600 dark:text-teal-400",     bg: "bg-teal-100 dark:bg-teal-900/50" },
  text:        { label: "文本保存", icon: MessageSquare,  color: "text-teal-600 dark:text-teal-400",     bg: "bg-teal-100 dark:bg-teal-900/50" },
};

export const SOURCE_LABEL: Record<KnowledgeSource, string> = {
  file: "文件上传",
  database: "数据库",
  repo: "代码仓库",
  environment: "环境配置",
  chat: "对话产出",
  text: "文本保存",
};
