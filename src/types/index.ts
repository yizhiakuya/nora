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

// ==========================================
// 知识库 / 上下文管线 (RAG Pipeline)
// ==========================================

/** 摄入来源 */
export type KnowledgeSource = "file" | "database" | "repo" | "environment" | "chat";

/** 知识文档（已摄入管线的一条记录） */
export interface KnowledgeDoc {
  id: number;
  name: string;
  source: KnowledgeSource;
  /** 切分后的 chunk 数量 */
  chunks: number;
  status: "indexed" | "processing" | "failed";
  size: string;
  updatedAt: string;
  /** 清洗质量评分 0-100 */
  quality: number;
}

/** 索引统计 */
export interface IndexStats {
  totalDocs: number;
  totalChunks: number;
  vectorDim: number;
  model: string;
  lastUpdate: string;
  pendingDocs: number;
  vectorReady: boolean;
  graphReady: boolean;
}

/** 清洗规则 */
export interface PipelineRule {
  id: number;
  name: string;
  description: string;
  enabled: boolean;
  category: "格式" | "去噪" | "分块" | "安全" | "质量";
}

/** 检索测试结果 */
export interface RetrievalResult {
  docName: string;
  source: KnowledgeSource;
  chunkIndex: number;
  score: number;
  snippet: string;
}

/** 数据图谱节点关联 */
export interface GraphProject {
  id: number;
  name: string;
  files: string[];
  tables: string[];
  services: string[];
}
