import { ApiError } from "./api/client";

/** 保留现有调用接口，错误原文直接展示。 */
export interface FriendlyError {
  message: string;
  hint?: string;
  kind: "network" | "provider" | "auth" | "rate-limit" | "timeout" | "approval" | "unknown";
  raw: string;
  retryable?: boolean;
}

export function humanizeError(error: unknown): FriendlyError {
  const message = typeof error === "string" ? error : error instanceof Error ? error.message : String(error ?? "");
  return {
    message,
    raw: message,
    kind: "unknown",
    retryable: error instanceof ApiError ? error.retryable : undefined,
  };
}
