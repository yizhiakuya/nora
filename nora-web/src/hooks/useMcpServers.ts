import { create } from "zustand";
import { fetchMcpServers, fetchMcpTools, type McpServer, type McpToolDetail } from "@/lib/api/mcpApi";

interface McpServersState {
  servers: McpServer[];
  /** server id → 缓存的工具清单(来自 tools_cache 快照,不触发远端调用) */
  toolsByServer: Record<number, McpToolDetail[]>;
  /** 拉取服务器列表(启用中的才有可引用工具) */
  syncServers: () => Promise<void>;
  /** 按需拉取某服务器的工具清单(已缓存则跳过) */
  loadTools: (serverId: number) => Promise<void>;
}

/**
 * MCP 服务器与工具清单的共享数据源(2026-09-17,@ 提及引用用):
 * 设置页与对话框引用菜单共用;工具清单来自后端 tools_cache 快照。
 */
export const useMcpServers = create<McpServersState>()((set, get) => ({
  servers: [],
  toolsByServer: {},
  syncServers: async () => {
    try {
      set({ servers: await fetchMcpServers() });
    } catch {
      /* 后端不可用时保留现状 */
    }
  },
  loadTools: async (serverId) => {
    if (get().toolsByServer[serverId]) return;
    try {
      const tools = await fetchMcpTools(serverId);
      set((s) => ({ toolsByServer: { ...s.toolsByServer, [serverId]: tools } }));
    } catch {
      /* 单服务器失败不影响其他 */
    }
  },
}));
