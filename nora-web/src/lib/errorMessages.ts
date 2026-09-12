/**
 * 错误人性化：把后端/网络层的原始错误翻译成用户能懂的文案，
 * 并给出"下一步怎么办"（研究结论：错误要像对话的一部分，永远给重试出口，
 * 不丢用户消息 —— aiuxdesign.guide 的 Error Handling & Fallback Design）。
 *
 * 两级策略(异常处理系统 2026-09-12):
 * 1. 结构化错误(后端 ApiError,带 category/hint/retryable):直接采用分类文案,
 *    hint 用后端给的可操作建议——不再猜;
 * 2. 字符串匹配(网络异常/网关 HTML/旧接口):保留原启发式兜底。
 */

import { ApiError } from "./api/client";

export type ErrorKind = "network" | "provider" | "auth" | "rate-limit" | "timeout" | "approval" | "unknown";

export interface FriendlyError {
  /** 展示给用户的一句话（人话，不带内部术语） */
  message: string;
  /** 建议动作（可选的第二行说明） */
  hint?: string;
  kind: ErrorKind;
  /** 原始错误串（折叠展示，便于排障） */
  raw: string;
  /** 是否值得重试(结构化错误才有;决定「重试」按钮) */
  retryable?: boolean;
}

/** 后端错误分类 → 前端 kind/文案(与后端 ErrorCategory 枚举对齐)。 */
const CATEGORY_FRIENDLY: Record<string, { kind: ErrorKind; message: string; hint?: string }> = {
  VALIDATION: { kind: "unknown", message: "请求内容有误", hint: "请检查输入后重试" },
  UNAUTHORIZED: { kind: "auth", message: "凭据无效或已过期", hint: "请在设置中检查 API Key 配置" },
  FORBIDDEN: { kind: "auth", message: "没有权限执行该操作", hint: "请检查账号权限或渠道限制" },
  NOT_FOUND: { kind: "unknown", message: "找不到请求的资源", hint: "刷新页面后重试" },
  CONFLICT: { kind: "unknown", message: "操作与当前状态冲突", hint: "稍后重试,或换个名称" },
  RATE_LIMITED: { kind: "rate-limit", message: "请求太频繁,触发了限流", hint: "请等几秒再重试,或切换其他模型" },
  DEPENDENCY: { kind: "provider", message: "依赖服务出错了", hint: "请稍后重试" },
  UNAVAILABLE: { kind: "network", message: "服务暂时不可用", hint: "可能在重启中,请稍后重试" },
  TIMEOUT: { kind: "timeout", message: "请求超时了", hint: "可重试;复杂问题可能需要更长时间" },
  INTERNAL: { kind: "unknown", message: "服务端处理本次请求时出错了", hint: "请稍后重试;若反复出现,请把 traceId 反馈给管理员" },
};

/** 从原始错误串提取 HTTP 状态码（如 "上游 400: ..." / "HTTP 502"） */
function extractStatus(raw: string): number | null {
  const m = raw.match(/\b(4\d\d|5\d\d)\b\s*[::]?/);
  return m ? Number(m[1]) : null;
}

/**
 * 主入口。raw 是 Error.message（可能来自后端 SSE error 事件、
 * fetch 网络异常、或网关 HTML 错误页文本）。
 * 传入结构化 ApiError 时优先按分类映射(不猜)。
 */
export function humanizeError(raw: string | ApiError | unknown): FriendlyError {
  // 结构化路径:后端明确给了分类 → 直接采用,不再字符串匹配
  if (raw instanceof ApiError && raw.category) {
    const mapped = CATEGORY_FRIENDLY[raw.category];
    if (mapped) {
      return {
        kind: mapped.kind,
        message: mapped.message,
        hint: raw.hint || mapped.hint,
        raw: raw.message,
        retryable: raw.retryable,
      };
    }
  }

  const text = typeof raw === "string" ? raw : raw instanceof Error ? raw.message : String(raw ?? "");
  const lower = text.toLowerCase();
  const status = extractStatus(text);

  // 1. 网络不可达（fetch 抛出的 TypeError 或我们的包装文案）
  if (
    lower.includes("cannot connect") ||
    lower.includes("networkerror") ||
    lower.includes("failed to fetch") ||
    lower.includes("service-unavailable") ||
    lower.includes("服务暂时不可用")
  ) {
    return {
      kind: "network",
      message: "暂时连不上服务，可能是网络中断或服务正在重启",
      hint: "请稍后重试；若持续失败，请检查后端服务状态",
      raw: text,
      retryable: true,
    };
  }

  // 2. 模型服务商配置问题（免费渠道限制 / key 无效 / 模型名不对）
  if (
    lower.includes("free tier") ||
    lower.includes("api key") ||
    lower.includes("apikey") ||
    lower.includes("unauthorized") ||
    lower.includes("forbidden") ||
    status === 401 ||
    status === 403
  ) {
    return {
      kind: "auth",
      message: "模型服务商拒绝了本次请求，通常是密钥无效或渠道有使用限制",
      hint: "可在「设置 → 模型管理」测试连通性，或切换其他模型后重试",
      raw: text,
    };
  }

  // 3. 限流
  if (lower.includes("rate limit") || lower.includes("限流") || lower.includes("too many") || status === 429) {
    return {
      kind: "rate-limit",
      message: "请求太频繁，触发了模型服务商的限流",
      hint: "请等几秒再重试，或切换其他模型",
      raw: text,
      retryable: true,
    };
  }

  // 4. 超时 / 网关上游超时
  if (lower.includes("timeout") || lower.includes("timed out") || lower.includes("超时")) {
    return {
      kind: "timeout",
      message: "模型响应超时了",
      hint: "复杂问题可能需要更长时间，可重试或换个问法",
      raw: text,
      retryable: true,
    };
  }

  // 5. 审批类（不该出现在 error 里，防御性兜底）
  if (lower.includes("approval") || lower.includes("审批")) {
    return { kind: "approval", message: "操作等待审批中", raw: text };
  }

  // 6. 服务端 5xx（网关/上游崩溃）
  if (status && status >= 500) {
    return {
      kind: "provider",
      message: "服务端处理本次请求时出错了",
      hint: "请稍后重试；若反复出现，请把会话 ID 反馈给管理员",
      raw: text,
      retryable: true,
    };
  }

  // 7. 模型服务商 4xx：上游明确拒绝（如截图中的免费渠道限制）
  if (status === 400 || lower.includes("provider") || lower.includes("上游") || lower.includes("upstream")) {
    return {
      kind: "provider",
      message: "模型服务商拒绝了本次请求",
      hint: "该模型/渠道可能不支持当前用法，可切换其他模型后重试",
      raw: text,
    };
  }

  // 8. 兜底：保留原文但给一句人话引导
  return {
    kind: "unknown",
    message: "本次回答没有生成成功",
    hint: "可点击重试；若持续失败，请复制会话 ID 反馈排障",
    raw: text,
  };
}
