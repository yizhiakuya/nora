import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DbConnection } from "@/types";
import { MOCK_CONNECTIONS } from "@/lib/devData";

interface ConnectionsState {
  connections: DbConnection[];
  addConnection: (conn: Omit<DbConnection, "id" | "status" | "activeConn" | "maxConn">) => DbConnection;
}

/**
 * 数据源连接唯一数据源：数据源页连接列表与「新建连接」弹窗共享。
 */
export const useConnections = create<ConnectionsState>()(
  persist(
    (set) => ({
      connections: MOCK_CONNECTIONS,
      addConnection: (partial) => {
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
    }),
    { name: "db-connections" }
  )
);
