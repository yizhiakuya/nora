import { DbConnection, DbTable, QueryHistory } from "@/types";

export const MOCK_CONNECTIONS: DbConnection[] = [
  { id: 1, name: "本地开发库",       engine: "postgresql", host: "localhost", port: 5432,  database: "myapp_dev",    status: "connected", activeConn: 3,  maxConn: 10 },
  { id: 2, name: "测试环境",         engine: "postgresql", host: "192.168.1.100", port: 5432, database: "myapp_test", status: "connected", activeConn: 5, maxConn: 20 },
  { id: 3, name: "本地 SQLite",      engine: "sqlite",     host: "—",         port: 0,     database: "./data/app.db", status: "connected", activeConn: 1, maxConn: 1 },
  { id: 4, name: "缓存 Redis",       engine: "redis",      host: "localhost", port: 6379,  database: "db0",          status: "error",     activeConn: 0, maxConn: 0 },
];

export const MOCK_TABLES: Record<string, DbTable[]> = {
  myapp_dev: [
    {
      name: "users", rows: 1284, size: "256 kB",
      columns: [
        { name: "id",         type: "SERIAL",    nullable: false, isPrimary: true, comment: "主键" },
        { name: "email",      type: "VARCHAR",   nullable: false, comment: "邮箱（唯一）" },
        { name: "name",       type: "VARCHAR",   nullable: true,  comment: "用户名" },
        { name: "created_at", type: "TIMESTAMP", nullable: false, comment: "创建时间" },
      ],
    },
    {
      name: "orders", rows: 8420, size: "1.2 MB",
      columns: [
        { name: "id",          type: "SERIAL",      nullable: false, isPrimary: true, comment: "主键" },
        { name: "user_id",     type: "INTEGER",     nullable: false, comment: "关联 users.id" },
        { name: "total",       type: "DECIMAL(10)", nullable: false, comment: "订单金额" },
        { name: "status",      type: "VARCHAR",     nullable: false, comment: "pending/paid/shipped" },
        { name: "scheduledAt", type: "TIMESTAMP",   nullable: true },
      ],
    },
    {
      name: "products", rows: 342, size: "128 kB",
      columns: [
        { name: "id",    type: "SERIAL",    nullable: false, isPrimary: true },
        { name: "name",  type: "VARCHAR",   nullable: false },
        { name: "price", type: "DECIMAL(8)", nullable: false },
      ],
    },
  ],
};

export const MOCK_QUERIES: QueryHistory[] = [
  { id: 1, sql: "SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY count DESC;", duration: "12ms", rowsAffected: 3, time: "14:02", status: "success" },
  { id: 2, sql: "SELECT * FROM users WHERE created_at > NOW() - INTERVAL '7 days' LIMIT 50;", duration: "8ms", rowsAffected: 23, time: "13:45", status: "success" },
  { id: 3, sql: "UPDATE products SET price = price * 1.1 WHERE id IN (1,2,3);", duration: "5ms", rowsAffected: 3, time: "13:30", status: "success" },
  { id: 4, sql: "SELECT * FROM nonexistent_table;", duration: "1ms", rowsAffected: 0, time: "13:22", status: "error" },
];

// ==========================================
// 环境控制台
// ==========================================

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
