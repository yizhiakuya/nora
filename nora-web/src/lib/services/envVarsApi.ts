import { requestJson } from "@/lib/api/client";
import type { EnvVar } from "@/types";

const path = "/chat/settings/env-vars";

export const envVarsApi = {
  list: () => requestJson<EnvVar[]>(path, { cache: "no-store" }),
  set: (key: string, value: Omit<EnvVar, "key">) => requestJson<EnvVar>(`${path}/${encodeURIComponent(key)}`, {
    method: "PUT",
    body: JSON.stringify(value),
  }),
  delete: (key: string) => requestJson<{ key: string; deleted: boolean }>(`${path}/${encodeURIComponent(key)}`, {
    method: "DELETE",
  }),
};
