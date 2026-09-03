import {
  KnowledgeDoc, IndexStats, PipelineRule, RetrievalResult, GraphProject, KnowledgeSource,
} from "@/types";
import { FileCode, Server, Database, File, MessageSquare } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const SOURCE_META: Record<KnowledgeSource, { label: string; icon: LucideIcon; color: string; bg: string }> = {
  file:        { label: "文件上传", icon: File,          color: "text-blue-600 dark:text-blue-400",   bg: "bg-blue-100 dark:bg-blue-900/50" },
  database:    { label: "数据库",   icon: Database,      color: "text-orange-600 dark:text-orange-400", bg: "bg-orange-100 dark:bg-orange-900/50" },
  repo:        { label: "代码仓库", icon: FileCode,      color: "text-purple-600 dark:text-purple-400", bg: "bg-purple-100 dark:bg-purple-900/50" },
  environment: { label: "环境配置", icon: Server,        color: "text-green-600 dark:text-green-400",   bg: "bg-green-100 dark:bg-green-900/50" },
  chat:        { label: "对话产出", icon: MessageSquare,  color: "text-teal-600 dark:text-teal-400",     bg: "bg-teal-100 dark:bg-teal-900/50" },
};

const BASE_DOCS: KnowledgeDoc[] = [
  { id: 1,  name: "NestJS部署手册.pdf",          source: "file",        chunks: 42,  status: "indexed",   size: "2.4 MB",  updatedAt: "2024-06-02 14:30", quality: 92 },
  { id: 2,  name: "服务器巡检记录.xlsx",         source: "file",        chunks: 18,  status: "indexed",   size: "1.2 MB",  updatedAt: "2024-06-01 10:15", quality: 87 },
  { id: 3,  name: "API接口设计规范.docx",        source: "file",        chunks: 36,  status: "indexed",   size: "845 KB",  updatedAt: "2024-05-28 09:00", quality: 90 },
  { id: 4,  name: "周会纪要_0520.txt",           source: "file",        chunks: 8,   status: "indexed",   size: "12 KB",   updatedAt: "2024-05-20 18:30", quality: 78 },
  { id: 5,  name: "数据库设计评审.md",           source: "file",        chunks: 24,  status: "processing", size: "340 KB", updatedAt: "2024-06-03 09:12", quality: 0 },
  { id: 6,  name: "users 表结构",              source: "database",    chunks: 6,   status: "indexed",   size: "4 KB",    updatedAt: "2024-06-02 08:00", quality: 95 },
  { id: 7,  name: "orders 表结构",             source: "database",    chunks: 8,   status: "indexed",   size: "6 KB",    updatedAt: "2024-06-02 08:00", quality: 93 },
  { id: 8,  name: "products 表结构",            source: "database",    chunks: 5,   status: "indexed",   size: "3 KB",    updatedAt: "2024-06-02 08:00", quality: 91 },
  { id: 9,  name: "api-gateway README.md",      source: "repo",        chunks: 30,  status: "indexed",   size: "56 KB",   updatedAt: "2024-06-01 16:40", quality: 88 },
  { id: 10, name: "worker-service README.md",   source: "repo",        chunks: 22,  status: "indexed",   size: "38 KB",   updatedAt: "2024-05-30 11:20", quality: 85 },
  { id: 11, name: ".env.production",            source: "environment", chunks: 3,   status: "indexed",   size: "1 KB",    updatedAt: "2024-06-03 07:45", quality: 98 },
  { id: 12, name: "docker-compose.yml",         source: "environment", chunks: 4,   status: "indexed",   size: "2 KB",    updatedAt: "2024-06-03 07:45", quality: 96 },
];

const EXTRA_DOCS: KnowledgeDoc[] = [
  { id: 13, name: "Docker入门笔记.pdf",          source: "file",        chunks: 28,  status: "indexed",   size: "1.8 MB",  updatedAt: "2024-04-15 10:00", quality: 89 },
  { id: 14, name: "payments 表结构",            source: "database",    chunks: 7,   status: "indexed",   size: "5 KB",    updatedAt: "2024-06-02 08:00", quality: 94 },
  { id: 15, name: "api-gateway OpenAPI.yml",    source: "repo",        chunks: 46,  status: "indexed",   size: "92 KB",   updatedAt: "2024-06-02 14:00", quality: 91 },
  { id: 16, name: "worker-service .env",        source: "environment", chunks: 2,   status: "indexed",   size: "1 KB",    updatedAt: "2024-06-03 07:45", quality: 97 },
  { id: 17, name: "系统架构设计_v3.docx",        source: "file",        chunks: 52,  status: "indexed",   size: "3.1 MB",  updatedAt: "2024-05-25 14:20", quality: 93 },
  { id: 18, name: "session_20240602_14.md",     source: "chat",        chunks: 12,  status: "indexed",   size: "8 KB",    updatedAt: "2024-06-02 15:00", quality: 82 },
  { id: 19, name: "session_20240601_10.md",     source: "chat",        chunks: 9,   status: "indexed",   size: "6 KB",    updatedAt: "2024-06-01 11:00", quality: 79 },
  { id: 20, name: "监控告警规则.yml",            source: "environment", chunks: 5,   status: "indexed",   size: "3 KB",    updatedAt: "2024-06-03 07:45", quality: 95 },
  { id: 21, name: "技术调研对比.xlsx",           source: "file",        chunks: 15,  status: "failed",    size: "620 KB",  updatedAt: "2024-05-22 09:00", quality: 0 },
  { id: 22, name: "Redis 配置说明",              source: "environment", chunks: 3,   status: "indexed",   size: "2 KB",    updatedAt: "2024-06-03 07:45", quality: 96 },
  { id: 23, name: "session_20240530_16.md",     source: "chat",        chunks: 7,   status: "indexed",   size: "5 KB",    updatedAt: "2024-05-30 17:00", quality: 81 },
  { id: 24, name: "学习笔记_TypeScript.md",      source: "file",        chunks: 20,  status: "indexed",   size: "1.1 MB",  updatedAt: "2024-05-28 16:00", quality: 86 },
];

export const ALL_DOCS: KnowledgeDoc[] = [...BASE_DOCS, ...EXTRA_DOCS];

export const MOCK_INDEX_STATS: IndexStats = {
  totalDocs: 24,
  totalChunks: 1847,
  vectorDim: 1536,
  model: "text-embedding-3-small",
  lastUpdate: "2 分钟前",
  pendingDocs: 3,
  vectorReady: true,
  graphReady: true,
};

export const MOCK_RULES: PipelineRule[] = [
  { id: 1, name: "PDF 页眉页脚去除",       description: "自动识别并移除重复页眉/页脚，避免噪音 chunk", enabled: true,  category: "去噪" },
  { id: 2, name: "Excel 空行过滤",         description: "跳过纯空行和全空列，减少无效分块",             enabled: true,  category: "格式" },
  { id: 3, name: "代码块函数边界切分",      description: "按函数/类边界切分代码，不硬切行数",            enabled: true,  category: "分块" },
  { id: 4, name: "环境变量值脱敏",         description: "检测 .env 中的密码/Token，只保留变量名",       enabled: true,  category: "安全" },
  { id: 5, name: "语义分块（512 token）",   description: "按段落/章节边界智能分块，不超 512 token",     enabled: true,  category: "分块" },
  { id: 6, name: "重复段落去重",           description: "跨文档去重相似度 > 0.95 的段落",             enabled: true,  category: "去噪" },
  { id: 7, name: "低质量 chunk 丢弃",      description: "信息密度低于阈值（如纯页码/空标题）自动丢弃",   enabled: true,  category: "质量" },
  { id: 8, name: "HTML 标签清除",          description: "导入 HTML/Markdown 时移除渲染无关标签",       enabled: false, category: "格式" },
];

export const MOCK_RETRIEVAL: RetrievalResult[] = [
  { docName: "NestJS部署手册.pdf",      source: "file",        chunkIndex: 12, score: 0.94, snippet: "…ECONNREFUSED 表示目标端口无进程监听，先到环境控制台确认 Redis 服务状态…" },
  { docName: "docker-compose.yml",      source: "environment", chunkIndex: 3,  score: 0.87, snippet: "…redis: image redis:7-alpine, ports 6379:6379, restart: unless-stopped…" },
  { docName: "周会纪要_0520.txt",        source: "file",        chunkIndex: 5,  score: 0.82, snippet: "…[14:15] 协作：Redis 偶发 ECONNREFUSED，怀疑连接池上限过低…" },
  { docName: "api-gateway README.md",   source: "repo",        chunkIndex: 18, score: 0.74, snippet: "…Redis 连接池默认 maxActive=50，可通过 REDIS_POOL_MAX 环境变量调整…" },
];

export const MOCK_GRAPH: GraphProject[] = [
  { id: 1, name: "电商后端",   files: ["NestJS部署手册.pdf", "服务器巡检记录.xlsx", "周会纪要_0520.txt"], tables: ["users", "orders", "payments"], services: ["api-gateway", "worker-service"] },
  { id: 2, name: "内部工具",   files: ["技术调研对比.xlsx", "API接口设计规范.docx"],                    tables: ["products"],                    services: ["cron-scheduler"] },
  { id: 3, name: "数据平台",   files: ["数据库设计评审.md"],                                          tables: ["analytics_events"],            services: ["report-generator"] },
];
