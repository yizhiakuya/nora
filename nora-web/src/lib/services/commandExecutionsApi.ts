import { requestJson } from "@/lib/api/client";
import type { CommandExecution } from "@/types";

const path = (session: string) => `/chat/sessions/${encodeURIComponent(session)}/commands`;
export const commandExecutionsApi = {
  list: (session: string) => requestJson<CommandExecution[]>(path(session), { cache: "no-store" }),
  get: (session: string, id: string) => requestJson<CommandExecution>(`${path(session)}/${encodeURIComponent(id)}`, { cache: "no-store" }),
  stop: (session: string, id: string) => requestJson<CommandExecution>(`${path(session)}/${encodeURIComponent(id)}/stop`, { method: "POST" }),
  clear: (session: string) => requestJson<boolean>(path(session), { method: "DELETE" }),
};
