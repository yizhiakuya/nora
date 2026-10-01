import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DbConnection } from "@/types";
import { datasourcesApi } from "@/lib/services/datasourcesApi";

interface ConnectionsState {
  connections: DbConnection[];
  /** 后端模式:拉取服务端连接列表 */
  syncFromBackend: () => Promise<void>;
  addConnection: (conn: Omit<DbConnection, "id" | "status" | "activeConn" | "maxConn"> & { username?: string; password?: string }) => Promise<DbConnection>;
  removeConnection: (id: number) => Promise<void>;
  markStatus: (id: number, status: DbConnection["status"]) => void;
}

/**
 * 数据源连接唯一数据源：数据源页连接列表与「新建连接」弹窗共享。
 * CRUD 走 datasource-service /api/datasources。
 */
export const useConnections = create<ConnectionsState>()(
  persist(
    (set) => ({
      connections: [],
      syncFromBackend: async () => {
        try {
          const connections = await datasourcesApi.listConnections();
          set({ connections });
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addConnection: async (partial) => {
        // 用户名可空(Redis 无认证/仅密码场景)
        const { username, password } = partial;
        const saved = await datasourcesApi.createConnection({
            name: partial.name,
            engine: partial.engine === "mysql" ? "mysql"
              : partial.engine === "redis" ? "redis" : "postgresql",
            host: partial.host,
            port: partial.port,
            database: partial.database,
            username: username ?? "",
            password: password ?? "",
          });
        // 创建成功才展示;测试失败时保留真实记录供用户排查或删除。
        set((state) => ({ connections: [...state.connections, saved] }));
        let result;
        try {
          result = await datasourcesApi.testConnection(saved.id);
        } catch (e) {
          set((state) => ({ connections: state.connections.map((c) => c.id === saved.id ? { ...c, status: "error" } : c) }));
          throw new Error(`连接记录已保存，但连通测试未完成：${e instanceof Error ? e.message : "请稍后重试"}`);
        }
        const tested = { ...saved, status: result.ok ? "connected" as const : "error" as const };
        set((state) => ({ connections: state.connections.map((c) => c.id === saved.id ? tested : c) }));
        return tested;
      },
      removeConnection: async (id) => {
        await datasourcesApi.deleteConnection(id);
        set((state) => ({ connections: state.connections.filter((c) => c.id !== id) }));
      },
      markStatus: (id, status) =>
        set((state) => ({
          connections: state.connections.map((c) => (c.id === id ? { ...c, status } : c)),
        })),
    }),
    { name: "db-connections" }
  )
);
