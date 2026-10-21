import { requestJson } from "@/lib/api/client";

export const authApi = {
  status: () => requestJson<{ authRequired: boolean; authenticated: boolean }>("/auth/status"),
  login: (token: string) => requestJson<void>("/auth/login", {
    method: "POST", body: JSON.stringify({ token }),
  }),
};
