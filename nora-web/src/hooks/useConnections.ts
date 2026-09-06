import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DbConnection } from "@/types";
import { datasourcesApi } from "@/lib/services/datasourcesApi";
import { USE_BACKEND } from "@/lib/api/client";

interface ConnectionsState {
  connections: DbConnection[];
  /** 后端模式:拉取服务端连接列表 */
  syncFromBackend: () => Promise<void>;
  addConnection: (conn: Omit<DbConnection, "id" | "status" | "activeConn" | "maxConn"> & { username?: string; password?: string }) => Promise<DbConnection> | DbConnection;
  removeConnection: (id: number) => void;
  markStatus: (id: number, status: DbConnection["status"]) => void;
}

/**
 * 数据源连接唯一数据源：数据源页连接列表与「新建连接」弹窗共享。
 * USE_BACKEND 时 CRUD 走 datasource-service /api/datasources。
 */
export const useConnections = create<ConnectionsState>()(
  persist(
    (set, get) => ({
      connections: [],
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const connections = await datasourcesApi.listConnections();
          if (connections.length > 0) {
            set({ connections });
          }
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addConnection: (partial) => {
        if (USE_BACKEND && partial.username && partial.password !== undefined) {
          const { username, password, ...rest } = partial;
          // 后端模式:先建记录,再立即测连通
          const optimistic: DbConnection = {
            ...rest,
            id: Date.now(),
            status: "connecting",
            activeConn: 0,
            maxConn: 10,
          };
          set((state) => ({ connections: [...state.connections, optimistic] }));
          return datasourcesApi
            .createConnection({
              name: partial.name,
              engine: partial.engine === "mysql" ? "mysql" : "postgresql",
              host: partial.host,
              port: partial.port,
              database: partial.database,
              username: username ?? "",
              password: password ?? "",
            })
            .then(async (saved) => {
              await datasourcesApi.testConnection(saved.id).catch(() => null);
              const tested = await datasourcesApi.listConnections();
              const serverRow = tested.find((c) => c.id === saved.id) ?? saved;
              set((state) => ({
                connections: state.connections.map((c) => (c.id === optimistic.id ? serverRow : c)),
              }));
              return serverRow;
            })
            .catch(() => optimistic);
        }
        // Mock 模式:保持旧行为
        const conn: DbConnection = {
          ...partial,
          id: Date.now(),
          status: "connected",
          activeConn: 1,
          maxConn: 10,
        };
        set((state) => ({ connections: [...state.connections, conn] }));
        return conn;
      },
      removeConnection: (id) => {
        set((state) => ({ connections: state.connections.filter((c) => c.id !== id) }));
        if (USE_BACKEND && id < 1e12) {
          datasourcesApi.deleteConnection(id).catch(() => { /* 本地已删 */ });
        }
      },
      markStatus: (id, status) =>
        set((state) => ({
          connections: state.connections.map((c) => (c.id === id ? { ...c, status } : c)),
        })),
    }),
    { name: "db-connections" }
  )
);
