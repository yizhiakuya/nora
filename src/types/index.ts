import { LucideIcon } from "lucide-react";

export interface Skill {
  id: number;
  name: string;
  desc: string;
  icon: LucideIcon;
  color: string;
  bg: string;
  category: string;
  enabled: boolean;
  isOfficial: boolean;
  /** 自定义技能创建时间（官方技能无） */
  createdAt?: string;
  /** OpenAPI Schema 原文（自定义技能） */
  schema?: string;
  /** 无鉴权 | Bearer Token | API Key */
  authType?: string;
}

export interface Task {
  id: string;
  name: string;
  agent: string;
  status: 'completed' | 'running' | 'scheduled' | 'failed';
  time: string;
  duration: string;
}

export interface FileItem {
  id: number;
  name: string;
  type: string;
  size: string;
  date: string;
  icon: LucideIcon;
  color: string;
  agent: string | null;
}

export interface FolderItem {
  name: string;
  count: number;
}

export interface DataSource {
  id: number;
  name: string;
  type: string;
  host: string;
  status: 'connected' | 'error';
  syncStatus: string;
  icon: LucideIcon;
  color: string;
  bg: string;
}

export type FilePreviewKind = "pdf" | "word" | "excel" | "image" | "text" | "unknown";

export interface FilePreview {
  kind: FilePreviewKind;
  pages?: number;
  text?: string;
  table?: { columns: string[]; rows: string[][] };
  imageUrl?: string;
}
