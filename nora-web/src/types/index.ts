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
  /** 所属文件夹 id;null/undefined = 根目录 */
  folderId?: number | null;
}

export interface FolderItem {
  name: string;
  count: number;
}

export type FilePreviewKind = "pdf" | "word" | "excel" | "image" | "video" | "audio" | "text" | "unknown" | "markdown" | "csv" | "json" | "html";

/** POST /api/viewer/resolve 与工具步骤共享的真实文件描述。 */
export interface ViewerFile {
  target: string;
  name: string;
  mimeType: string;
  size: number;
  modifiedAt: string | null;
  version: string;
  previewKind: FilePreviewKind | "office";
  capabilities: { preview: boolean; source: boolean; download: boolean; edit: boolean; attach: boolean };
  /** 旧相册入口的原件地址，仅在浏览器内保留，不写入工具步骤。 */
  originalUrl?: string;
  delivery?: { status: "registered" | "failed" | "not_requested"; artifactId?: number | null; error?: string | null } | null;
}

export interface ViewerFileError { target: string; code: string; message: string }

export interface FilePreview {
  kind: FilePreviewKind;
  pages?: number;
  text?: string;
  table?: { columns: string[]; rows: string[][] };
  imageUrl?: string;
  /** 视频/音频原始字节 URL(经 /api/files/{id}/raw,<video>/<audio> 直接播放) */
  mediaUrl?: string;
  hash?: string | null;
  truncated?: boolean;
  tableTruncated?: boolean;
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
  /** 来源文件 id(source='file' 时;可跳转文件中心预览)。 */
  sourceId?: number | null;
  /** 构建失败原因（status='failed' 时;阶段 A 起可用）。 */
  error?: string | null;
  /** 非致命解析告警（如超限截断;文档仍可检索）。 */
  warning?: string | null;
  /** 资料库 id（阶段 B;null = 未归库）。 */
  baseId?: number | null;
  /** 启用状态（阶段 B;false = 退出检索,数据保留）。 */
  enabled?: boolean | null;
  /** 分段模式（阶段 B:plain / parent_child）。 */
  chunkMode?: string | null;
}

/** 资料库分组（阶段 B;阶段 D 加库级检索配置） */
export interface KnowledgeBase {
  id: number;
  name: string;
  description: string | null;
  isDefault: boolean;
  docCount: number;
  /** 库级检索配置 JSON 串（阶段 D;null=用全局默认）。 */
  retrievalConfig?: string | null;
}

/** 检索记录（阶段 B;「为什么找不到」回溯） */
export interface RetrievalLog {
  id: number;
  query: string;
  topK: number | null;
  scopeJson: string | null;
  status: string;
  resultCount: number;
  durationMs: number | null;
  detailJson: string | null;
  createdAt: string;
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
}

/** 对话引用来源 */
export interface Citation {
  docName: string;
  source: KnowledgeSource;
  chunkIndex: number;
  score: number;
  snippet: string;
  /** 稳定块 id（knowledge_chunk.id;用户引用为 null）。 */
  chunkId?: number | null;
  /** 命中通道：vector/keyword/both（用户引用为 null）。 */
  matchChannel?: string | null;
  /** 向量通道原始分；null = 未命中该通道。 */
  vectorScore?: number | null;
  /** 关键词通道原始分；null = 未命中该通道。 */
  keywordScore?: number | null;
}

/** 检索测试结果（含通道细分与完整证据;2026-09-22 阶段 A） */
export interface RetrievalResult {
  docName: string;
  source: KnowledgeSource;
  chunkIndex: number;
  score: number;
  snippet: string;
  chunkId?: number | null;
  matchChannel?: string | null;
  vectorScore?: number | null;
  keywordScore?: number | null;
  /** 完整块正文（模型证据;列表用 snippet 预览）。 */
  content?: string | null;
}

/** 检索结果集（带通道状态;2026-09-22 阶段 A/B） */
export interface RetrievalOutcome {
  /** ok / no_match / degraded / unavailable */
  status: "ok" | "no_match" | "degraded" | "unavailable";
  results: RetrievalResult[];
  vector: { ok: boolean; error: string | null };
  keyword: { ok: boolean; error: string | null };
  /** 可选重排状态（阶段 B:disabled/applied/failed）。 */
  rerank?: { state: "disabled" | "applied" | "failed"; model: string | null; error: string | null } | null;
}

/** 文档的一个分块(详情抽屉展示) */
export interface KnowledgeChunk {
  /** knowledge_chunk.id（阶段 D:分段级管理的稳定标识）。 */
  id?: number;
  chunkIndex: number;
  content: string;
  tokenCount: number;
  /** 字符数(与 tokenCount 不同量纲,供 UI 展示) */
  length: number;
  /** 父子模式:所属章节序号;null = 非父子模式(或旧数据)。 */
  parentIndex?: number | null;
  /** 父子模式:所属章节完整正文(供详情抽屉展开)。 */
  parentContent?: string | null;
  /** 分段启用状态（阶段 D;false=退出检索）。 */
  enabled?: boolean;
  /** 人工编辑过（阶段 D;重新分段会覆盖）。 */
  edited?: boolean;
  /** auto=自动分段;manual=手动新增。 */
  origin?: string;
}

/** GET /api/rag/docs/{id} 的返回:文档 + 它的 chunk 正文 */
export interface DocDetail {
  doc: KnowledgeDoc;
  chunks: KnowledgeChunk[];
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
  /** 纳管源类型:DOCKER=容器(可启停)| FILE=进程日志源(只观测)| PROC=平台拉起的程序(可启停/守护重启);旧数据缺省视为 DOCKER */
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
  /** 存量规则缺真实日程/连接(M4-05):界面显示「需配置」而非正常启用。 */
  needsConfig?: boolean;
}

/** 自动任务执行记录 */
export interface ExecutionRecord {
  id: number;
  /** 所属规则 id(后端返回;「重试」按它重新触发) */
  ruleId?: number;
  ruleName: string;
  /** 规则原始动作 JSON(B2,2026-09-27;「设为定期任务」复用它而非结果文本) */
  actionJson?: string | null;
  time: string;
  duration: string;
  /**
   * 终态(F2,2026-09-26):后端统一口径——
   * success / partial(有成果但有未完成项)/ failed / cancelled / unknown(结果未知)
   * / running / missed_schedule(计划点错过未补跑)。
   */
  status: "success" | "partial" | "failed" | "cancelled" | "unknown" | "running" | "missed_schedule";
  /** 完整执行结果(不截断;列表 UI 自行用摘要展示,详情面板读全文) */
  detail: string;
  /** 列表用摘要(首行,≤120 字;detail 的派生视图,不额外存储) */
  detailSummary?: string;
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
