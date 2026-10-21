package com.nora.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 错误分类(异常处理系统的分类核心,2026-09-12)。
 *
 * <p>按「用户/运维该做什么」划分,而不是按 HTTP code 机械映射:
 * 前端据此选文案与操作按钮,后端据此定日志级别。HTTP 状态与
 * {@code retryable} 是分类的属性,不是判断依据。
 */
public enum ErrorCategory {

    /** 输入不合法(参数缺失/格式错/越界):用户改输入即可。 */
    VALIDATION(HttpStatus.BAD_REQUEST, false),

    /** 凭据缺失或无效(API key 错误等):用户需重新配置。 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false),

    /** 无权限(账号/渠道限制):用户需换账号或申请。 */
    FORBIDDEN(HttpStatus.FORBIDDEN, false),

    /** 资源不存在:刷新或检查 id/名称。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, false),

    /** 状态冲突(重名/并发修改):换个名字或重试。 */
    CONFLICT(HttpStatus.CONFLICT, false),

    /** 限流(模型渠道/外部 API):等待后重试。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),

    /** 下游依赖失败(数据库/LLM 上游/MCP/Docker):检查下游或重试。 */
    DEPENDENCY(HttpStatus.BAD_GATEWAY, true),

    /** 服务不可用(重启中/未注册/网络不可达):稍后重试。 */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),

    /** 超时(下游/整体):可重试,或加大超时。 */
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),

    /** 预期外错误(代码 bug):报障(traceId 定位)。 */
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCategory(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    /** 同类错误的默认重试语义;单条异常可显式覆盖。 */
    public boolean retryable() {
        return retryable;
    }

    /** HTTP code → 分类(兼容旧 {@code BusinessException(int code, ...)} 的自动映射)。 */
    public static ErrorCategory fromHttpCode(int code) {
        return switch (code) {
            case 400, 422 -> VALIDATION;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 429 -> RATE_LIMITED;
            case 502 -> DEPENDENCY;
            case 503 -> UNAVAILABLE;
            case 504 -> TIMEOUT;
            default -> code >= 500 ? INTERNAL : VALIDATION;
        };
    }
}
