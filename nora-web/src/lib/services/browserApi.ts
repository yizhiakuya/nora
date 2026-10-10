import { requestJson } from "@/lib/api/client";
import { withAuthToken } from "@/lib/auth";
import type { BrowserReceipt, BrowserState, BrowserTaskConfig, BrowserResultPage } from "@/types";

const path = (sessionId: string) => `/workspace/browser/${encodeURIComponent(sessionId)}`;

export const browserApi = {
  state: (sessionId: string, signal?: AbortSignal, history = false) => requestJson<BrowserState>(`${path(sessionId)}${history ? "?history=true" : ""}`, { signal }),
  control: (sessionId: string, args: Record<string, unknown>) => requestJson<BrowserState>(path(sessionId), {
    method: "POST", body: JSON.stringify(args), signal: AbortSignal.timeout(45000),
  }),
  verify: (sessionId: string, receiptId: string) => requestJson<BrowserReceipt>(`${path(sessionId)}/tasks`, {
    method: "POST", body: JSON.stringify({ action: "verify", receiptId }),
  }),
  plan: (sessionId: string, config: BrowserTaskConfig) => requestJson<{ id: string }>(`${path(sessionId)}/tasks`, {
    method: "POST", body: JSON.stringify({ action: "plan", ...config }),
  }),
  rows: (sessionId: string, planId: string, receiptId?: string, offset = 0, signal?: AbortSignal) => requestJson<BrowserResultPage>(`${path(sessionId)}/tasks`, {
    method: "POST", body: JSON.stringify({ action: "rows", planId, receiptId, offset, limit: 50 }), signal,
  }),
  decide: (sessionId: string, planId: string, action: "approve" | "resample" | "cancel", feedback = "") => requestJson<{ id: string }>(`${path(sessionId)}/tasks`, {
    method: "POST", body: JSON.stringify({ action, planId, feedback }),
  }),
  adjust: (sessionId: string, receiptId: string, rowIds: string[]) => requestJson<BrowserReceipt>(`${path(sessionId)}/tasks`, {
    method: "POST", body: JSON.stringify({ action: "adjust", receiptId, rowIds }),
  }),
  frameUrl: (sessionId: string, frame: number) => withAuthToken(`/api${path(sessionId)}/frame?t=${frame}`),
};
