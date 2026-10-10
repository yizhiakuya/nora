import { create } from "zustand";
import { persist } from "zustand/middleware";
import { toast } from "sonner";
import type { AgentSettings } from "@/types";
import type { PermissionMode } from "@/lib/api/chatApi";
import { fetchAgentSettings, saveAgentSettings } from "@/lib/api/agentApi";

interface AgentSettingsState {
  permissionMode: PermissionMode;
  reasoningLevelOverride?: string;
  commandTimeoutSeconds: number;
  setCommandTimeoutSeconds: (value: number) => Promise<boolean>;
  ready: boolean;
  loading: boolean;
  saving: boolean;
  error: string | null;
  setPermissionMode: (mode: PermissionMode) => Promise<boolean>;
  setReasoningLevelOverride: (level: string | undefined) => Promise<boolean>;
  syncFromBackend: () => Promise<void>;
}

/** 聊天与管理页共享同一配置；服务端为准，缓存只用于首次加载。 */
export const useAgentSettings = create<AgentSettingsState>()(persist((set, get) => {
  const apply = (settings: AgentSettings) => set({
    permissionMode: settings.permissionMode ?? "assist",
    reasoningLevelOverride: settings.reasoningLevel ?? undefined,
    commandTimeoutSeconds: settings.commandTimeoutSeconds ?? 1800,
    ready: true, error: null,
  });
  const save = async (patch: AgentSettings) => {
    if (get().saving || get().loading) return false;
    set({ saving: true });
    try {
      apply(await saveAgentSettings(patch));
      return true;
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error));
      return false;
    } finally { set({ saving: false }); }
  };
  return {
    permissionMode: "assist", reasoningLevelOverride: undefined,
    commandTimeoutSeconds: 1800,
    setCommandTimeoutSeconds: commandTimeoutSeconds => save({ commandTimeoutSeconds }),
    ready: false, loading: false, saving: false, error: null,
    setPermissionMode: permissionMode => save({ permissionMode }),
    setReasoningLevelOverride: level => save({ reasoningLevel: level ?? null }),
    syncFromBackend: async () => {
      if (get().loading || get().saving) return;
      set({ loading: true, error: null });
      try { apply(await fetchAgentSettings()); }
      catch (error) { set({ error: error instanceof Error ? error.message : String(error) }); }
      finally { set({ loading: false }); }
    },
  };
}, {
  name: "chat-agent-settings",
  partialize: state => ({ permissionMode: state.permissionMode, reasoningLevelOverride: state.reasoningLevelOverride }),
}));
