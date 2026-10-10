import { createContext, useContext } from "react";
import type { useBrowser } from "@/hooks/useBrowser";
import type { WorkspaceTab } from "@/types";

export const WorkspaceContext = createContext<{
  sessionId: string;
  browser: ReturnType<typeof useBrowser>;
  tabs: WorkspaceTab[];
  activeId: string;
  selectTab: (id: string) => void;
  closeTab: (id: string) => void;
} | null>(null);

export function useWorkspace() {
  const workspace = useContext(WorkspaceContext);
  if (!workspace) throw new Error("工作区尚未挂载");
  return workspace;
}
