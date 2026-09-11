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
  /** OpenAPI Schema 原文（自定义技能；旧版字段，保留兼容） */
  schema?: string;
  /** 无鉴权 | Bearer Token | API Key（旧版字段，保留兼容） */
  authType?: string;
  /** 指令正文（指令型技能；列表接口不返回，详情加载后填充） */
  instructions?: string;
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

/**
 * 摄入来源。
 * `text` 是对话「保存到知识库」等纯文本入库的来源(后端 rag-service 的
 * POST /rag/index/text 写 source='text'),此前未列入该联合类型,导致
 * 文档库按 SOURCE_ORDER 分组时静默丢失这类文档。
 */
export type KnowledgeSource = "file" | "database" | "repo" | "environment" | "chat" | "text";

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

/** 对话引用来源 */
export interface Citation {
  docName: string;
  source: KnowledgeSource;
  chunkIndex: number;
  score: number;
  snippet: string;
}

/** 检索测试结果 */
export interface RetrievalResult {
  docName: string;
  source: KnowledgeSource;
  chunkIndex: number;
  score: number;
  snippet: string;
}

/** 文档的一个分块(详情抽屉展示) */
export interface KnowledgeChunk {
  chunkIndex: number;
  content: string;
  tokenCount: number;
  /** 字符数(与 tokenCount 不同量纲,供 UI 展示) */
  length: number;
}

/** GET /api/rag/docs/{id} 的返回:文档 + 它的 chunk 正文 */
export interface DocDetail {
  doc: KnowledgeDoc;
  chunks: KnowledgeChunk[];
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

// ===== 环境控制台 / 自动任务 / 环境变量(原 devData 类型,后端契约层) =====

export interface ServiceInstance {
  id: number;
  name: string;
  image: string;
  port: number;
  status: "running" | "stopped" | "error";
  health: "healthy" | "degraded" | "down";
  uptime: string;
  cpu: string;
  memory: string;
  /** 纳管源类型:DOCKER=容器(可启停)| FILE=进程日志源(只观测)| PROC=平台拉起的程序(可启停/守护重启);mock/旧数据缺省视为 DOCKER */
  kind?: "DOCKER" | "FILE" | "PROC";
  /** PROC 源:启动命令(java -jar xx.jar / node server.js …) */
  command?: string;
  /** PROC 源:工作目录 */
  workDir?: string;
  /** FILE 源:日志文件路径(日志 tail / AI 分析用) */
  fileLogPath?: string;
  /** 后端纳管源 id(FILE 源日志接口按它取数);后端模式下必填 */
  sourceId?: number;
  /** 状态补充说明(如「日志活跃于 2 分钟前」「容器不存在」) */
  detail?: string;
}

export interface LogEntry {
  time: string;
  level: "info" | "warn" | "error";
  service: string;
  message: string;
}

// ==========================================
// 自动任务
// ==========================================

export interface AutomationRule {
  id: number;
  name: string;
  trigger: string;
  action: string;
  enabled: boolean;
  lastRun: string;
  nextRun?: string;
  status: "active" | "paused" | "error";
}

/** 自动任务执行记录 */
export interface ExecutionRecord {
  id: number;
  ruleName: string;
  time: string;
  duration: string;
  status: "success" | "failed" | "running";
  detail: string;
}

export interface EnvVar {
  key: string;
  value: string;
  secret: boolean;
  /** 用途说明（显示在变量名下方） */
  note?: string;
}

/** 用户自定义凭据与环境变量（设置 → 环境变量）：
 *  在自定义技能、自动任务配置中以 {{KEY}} 引用；secret 值对 AI 自动脱敏。 */
