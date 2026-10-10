import { requestJson } from "@/lib/api/client";
import type { ChatContextUsage } from "@/lib/api/chatApi";

/** GET /chat/sessions/:id/context；摘要计费与模型工作上下文分别记录。 */
export interface SessionContextView {
  checkpoint: {
    boundaryId: string;
    summary: string;
    archivePath: string;
    beforeTokens: number;
    contextUsage: ChatContextUsage;
    summaryInputTokens: number | null;
    summaryOutputTokens: number | null;
    createdAt: string;
  } | null;
  latestUsage: ChatContextUsage | null;
  latestRequestUsage: ChatContextUsage | null;
}

export function fetchSessionContext(sessionId: string): Promise<SessionContextView> {
  return requestJson(`/chat/sessions/${encodeURIComponent(sessionId)}/context`, { cache: "no-store" });
}
