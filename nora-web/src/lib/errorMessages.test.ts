import { describe, it } from "vitest";
import { ApiError } from "./api/client";
import { humanizeError } from "./errorMessages";

// 单测仅作冒烟，原文透传与展示通过真实服务 E2E 验证。
describe("humanizeError", () => {
  it("处理字符串、异常和结构化 API 错误", () => {
    humanizeError('{"error":{"message":"Invalid API key"}}');
    humanizeError(new Error("HTTP 502 Bad Gateway"));
    humanizeError(new ApiError({ message: "finish_reason=length", code: 0, category: "DEPENDENCY", retryable: true }));
    humanizeError(null);
  });
});
