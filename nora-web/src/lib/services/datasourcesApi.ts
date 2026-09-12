import { requestJson, USE_BACKEND } from "@/lib/api/client";
import type { DbConnection } from "@/types";

/** 后端 db_connection 行(camelCase,密码脱敏) */
export interface BackendConnection {
  id: number;
  name: string;
  engine: "postgresql" | "mysql";
  host: string;
  port: number | null;
  database: string;
  username: string;
  maskedPassword: string;
  status: string;
}

/** 后端表字段(含 JDBC 元数据:注释/可空/主键/默认值) */
export interface BackendColumn {
  name: string;
  type: string;
  /** 列注释(数据库无注释时为空串) */
  comment: string;
  /** 是否可空 */
  nullable: boolean;
  /** 是否主键 */
  primaryKey: boolean;
  /** 默认值表达式;无默认值为 null */
  defaultValue: string | null;
}

/** 后端表结构 */
export interface BackendTable {
  /** 所属 schema/catalog(如 pg 的 public);无 schema 的引擎为 null */
  schema?: string | null;
  name: string;
  /** 表注释(数据库无注释时为空串) */
  comment: string;
  columns: BackendColumn[];
}

/** 后端查询结果 */
export interface BackendQueryResult {
  columns: string[];
  rows: (string | null)[][];
  rowCount: number;
  durationMs: number;
}

/** 后端查询历史 */
export interface BackendHistory {
  id: number;
  sql: string;
  durationMs: number | null;
  rowsAffected: number;
  status: string;
  executedAt: string;
}

function toConnection(c: BackendConnection): DbConnection {
  return {
    id: c.id,
    name: c.name,
    engine: c.engine,
    host: c.host ?? "",
    port: c.port ?? 5432,
    database: c.database ?? "",
    status: c.status === "connected" ? "connected" : c.status === "error" ? "error" : "connecting",
    activeConn: 0,
    maxConn: 10,
  };
}

/**
 * 数据源后端接入层(USE_BACKEND 开关):
 * - listConnections  → GET    /api/datasources            → DbConnection[]
 * - createConnection → POST   /api/datasources            → DbConnection
 * - deleteConnection → DELETE /api/datasources/{id}
 * - testConnection   → POST   /api/datasources/{id}/test  → {ok, message, latencyMs}
 * - fetchSchema      → GET    /api/datasources/{id}/schema → tables
 * - runQuery         → POST   /api/datasources/{id}/query → result(只读,限 200 行)
 * - fetchHistory     → GET    /api/datasources/{id}/history → history[]
 */
export const datasourcesApi = {
  async listConnections(): Promise<DbConnection[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendConnection[]>("/datasources");
    return items.map(toConnection);
  },

  async createConnection(input: {
    name: string;
    engine: "postgresql" | "mysql";
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
  }): Promise<DbConnection> {
    const item = await requestJson<BackendConnection>("/datasources", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return toConnection(item);
  },

  async deleteConnection(id: number): Promise<void> {
    await requestJson<void>(`/datasources/${id}`, { method: "DELETE" });
  },

  async testConnection(
    id: number
  ): Promise<{ ok: boolean; message: string; latencyMs: number | null }> {
    return requestJson(`/datasources/${id}/test`, { method: "POST" });
  },

  async fetchSchema(id: number): Promise<BackendTable[]> {
    const snapshot = await requestJson<{ tables: BackendTable[] }>(`/datasources/${id}/schema`);
    return snapshot.tables ?? [];
  },

  async runQuery(id: number, sql: string): Promise<BackendQueryResult> {
    return requestJson<BackendQueryResult>(`/datasources/${id}/query`, {
      method: "POST",
      body: JSON.stringify({ sql }),
    });
  },

  async fetchHistory(id: number, limit = 50): Promise<BackendHistory[]> {
    return requestJson<BackendHistory[]>(`/datasources/${id}/history?limit=${limit}`);
  },
};

export { toConnection };
