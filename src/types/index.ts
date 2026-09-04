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

export interface FileItem {
  id: number;
  name: string;
  type: string;
  size: string;
  date: string;
  icon: LucideIcon;
  color: string;
  /** AI 是否已索引入知识库 */
  indexed: boolean;
}

export interface FolderItem {
  name: string;
  count: number;
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

// ==========================================
// 开发者工作台 (Developer Console)
// ==========================================

/** 数据库连接 */
export interface DbConnection {
  id: number;
  name: string;
  engine: "postgresql" | "mysql" | "sqlite" | "redis";
  host: string;
  port: number;
  database: string;
  status: "connected" | "error" | "connecting";
  /** 连接池活跃数 */
  activeConn: number;
  maxConn: number;
}

/** 数据库表结构 */
export interface DbTable {
  name: string;
  rows: number;
  size: string;
  columns: DbColumn[];
}

export interface DbColumn {
  name: string;
  type: string;
  nullable: boolean;
  isPrimary?: boolean;
  comment?: string;
}

/** 查询历史 */
export interface QueryHistory {
  id: number;
  sql: string;
  duration: string;
  rowsAffected: number;
  time: string;
  status: "success" | "error";
}
