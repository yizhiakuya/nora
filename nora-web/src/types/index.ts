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
}

export const MOCK_SERVICES: ServiceInstance[] = [
  { id: 1, name: "api-gateway",   image: "node:20-alpine",   port: 3000, status: "running", health: "healthy", uptime: "3d 4h",   cpu: "12%",  memory: "245 MB" },
  { id: 2, name: "worker-service", image: "node:20-alpine",  port: 3001, status: "running", health: "healthy", uptime: "3d 4h",   cpu: "8%",   memory: "180 MB" },
  { id: 3, name: "postgres",       image: "postgres:16",     port: 5432, status: "running", health: "healthy", uptime: "7d 12h",  cpu: "4%",   memory: "420 MB" },
  { id: 4, name: "redis",          image: "redis:7-alpine",  port: 6379, status: "stopped", health: "down",    uptime: "—",       cpu: "0%",   memory: "0 MB" },
];

export interface LogEntry {
  time: string;
  level: "info" | "warn" | "error";
  service: string;
  message: string;
}

export const MOCK_LOGS: LogEntry[] = [
  { time: "14:02:31", level: "error", service: "api-gateway",   message: "Connection refused: redis://localhost:6379 (ECONNREFUSED)" },
  { time: "14:02:32", level: "warn",  service: "api-gateway",   message: "Retrying Redis connection in 5s... (attempt 3/10)" },
  { time: "14:02:30", level: "info",  service: "worker-service", message: "Job queue idle, waiting for tasks..." },
  { time: "14:02:28", level: "info",  service: "postgres",       message: "Checkpoint complete: wrote 842 buffers" },
  { time: "14:02:25", level: "error", service: "worker-service", message: "Failed to publish event: cache_unavailable" },
  { time: "14:02:20", level: "info",  service: "api-gateway",   message: "GET /api/v2/products 200 12ms" },
  { time: "14:02:15", level: "warn",  service: "postgres",       message: "Connection pool usage at 80% (16/20)" },
];

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

export const MOCK_AUTOMATIONS: AutomationRule[] = [
  { id: 1, name: "每日数据备份",    trigger: "每日 02:00",         action: "pg_dump → S3",           enabled: true,  lastRun: "今天 02:00", nextRun: "明天 02:00", status: "active" },
  { id: 2, name: "CSV 上传入库",    trigger: "文件上传 (.csv)",    action: "解析 → 批量插入 orders",  enabled: true,  lastRun: "1 小时前",   status: "active" },
  { id: 3, name: "服务异常告警",    trigger: "日志 ERROR ≥ 5/min", action: "AI 诊断 → 发通知",       enabled: true,  lastRun: "3 分钟前",   status: "active" },
  { id: 4, name: "周报生成",        trigger: "每周五 17:00",       action: "查询汇总 → Markdown 报告", enabled: true,  lastRun: "3 天前",    nextRun: "周五 17:00", status: "active" },
  { id: 5, name: "缓存预热",        trigger: "服务启动后",         action: "刷新热点数据到 Redis",    enabled: false, lastRun: "—",        status: "paused" },
];

/** 自动任务执行记录 */
export interface ExecutionRecord {
  id: number;
  ruleName: string;
  time: string;
  duration: string;
  status: "success" | "failed" | "running";
  detail: string;
}

export const MOCK_EXECUTIONS: ExecutionRecord[] = [
  { id: 1, ruleName: "服务异常告警",     time: "14:02", duration: "1.2s",  status: "success", detail: "AI 诊断 ECONNREFUSED → 已生成修复建议" },
  { id: 2, ruleName: "每日数据备份",     time: "02:00", duration: "42s",   status: "success", detail: "pg_dump → S3 (myapp_dev, 128 MB)" },
  { id: 3, ruleName: "CSV 上传入库",     time: "13:05", duration: "3.8s",  status: "success", detail: "orders_export.csv → 826 行已插入" },
  { id: 4, ruleName: "周报生成",         time: "周五 17:00", duration: "12s", status: "failed", detail: "查询超时（>10s）：analytics_events 表锁等待" },
];

export interface EnvVar {
  key: string;
  value: string;
  secret: boolean;
  /** 用途说明（显示在变量名下方） */
  note?: string;
}

/** 用户自定义凭据与环境变量（设置 → 环境变量）：
 *  在自定义技能、自动任务配置中以 {{KEY}} 引用；secret 值对 AI 自动脱敏。 */
