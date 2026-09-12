package com.nora.common.exception;

/**
 * 业务异常(异常处理系统,2026-09-12 升级)。
 *
 * <p>携带四要素,供 REST 层与前端消费:
 * <ul>
 *   <li>{@code category} 错误分类(决定 HTTP 状态/日志级别/前端文案)</li>
 *   <li>{@code errorCode} 稳定错误码(如 {@code DS_CONNECT_FAILED}),供前端精确分支/文案映射</li>
 *   <li>{@code hint} 可操作的建议(「检查数据库地址后重试」),直接展示给用户</li>
 *   <li>{@code retryable} 是否值得重试(前端据此显示「重试」按钮)</li>
 * </ul>
 *
 * <p>兼容:旧的 {@code BusinessException(int code, String message)} 构造器保留,
 * 按 HTTP code 自动映射分类(见 {@link ErrorCategory#fromHttpCode});
 * 新代码请用语义化构造器({@link #validation}/{@link #notFound}/{@link #dependency} 等),
 * 让分类不再依赖数字。
 */
public class BusinessException extends RuntimeException {

    private final int code;
    private final ErrorCategory category;
    private final String errorCode;
    private final String hint;
    private final Boolean retryable;

    /**
     * 兼容构造器(旧调用点):按 HTTP code 映射分类。
     *
     * @deprecated 新代码用语义化构造器;此构造器仅保证旧调用点零改动升级。
     */
    @Deprecated
    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    /** 兼容构造器(带 cause)。 */
    @Deprecated
    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.category = ErrorCategory.fromHttpCode(code);
        this.errorCode = null;
        this.hint = null;
        this.retryable = null;
    }

    /** 语义化构造器:分类 + 消息。 */
    public BusinessException(ErrorCategory category, String message) {
        this(category, null, message, null, null, null);
    }

    /** 语义化构造器:分类 + 稳定错误码 + 消息 + 提示。 */
    public BusinessException(ErrorCategory category, String errorCode, String message, String hint) {
        this(category, errorCode, message, hint, null, null);
    }

    /** 语义化构造器(带 cause)。 */
    public BusinessException(ErrorCategory category, String errorCode, String message,
                             String hint, Throwable cause) {
        this(category, errorCode, message, hint, null, cause);
    }

    /** 完整构造器。 */
    public BusinessException(ErrorCategory category, String errorCode, String message,
                             String hint, Boolean retryable, Throwable cause) {
        super(message, cause);
        this.category = category == null ? ErrorCategory.INTERNAL : category;
        this.code = this.category.status().value();
        this.errorCode = errorCode;
        this.hint = hint;
        this.retryable = retryable;
    }

    // ---------- 语义化工厂(常用路径,短且可读) ----------

    public static BusinessException validation(String message) {
        return new BusinessException(ErrorCategory.VALIDATION, message);
    }

    public static BusinessException validation(String errorCode, String message, String hint) {
        return new BusinessException(ErrorCategory.VALIDATION, errorCode, message, hint);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCategory.NOT_FOUND, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(ErrorCategory.CONFLICT, message);
    }

    /** 下游依赖失败(DB/LLM/MCP/Docker):可重试。 */
    public static BusinessException dependency(String errorCode, String message, String hint) {
        return new BusinessException(ErrorCategory.DEPENDENCY, errorCode, message, hint);
    }

    /** 下游依赖失败(带 cause)。 */
    public static BusinessException dependency(String errorCode, String message, String hint, Throwable cause) {
        return new BusinessException(ErrorCategory.DEPENDENCY, errorCode, message, hint, cause);
    }

    /** 限流:可重试。 */
    public static BusinessException rateLimited(String errorCode, String message, String hint) {
        return new BusinessException(ErrorCategory.RATE_LIMITED, errorCode, message, hint);
    }

    /** 上游凭据问题。 */
    public static BusinessException unauthorized(String errorCode, String message, String hint) {
        return new BusinessException(ErrorCategory.UNAUTHORIZED, errorCode, message, hint);
    }

    /** 超时:可重试。 */
    public static BusinessException timeout(String errorCode, String message, String hint) {
        return new BusinessException(ErrorCategory.TIMEOUT, errorCode, message, hint);
    }

    // ---------- 读取 ----------

    public int getCode() {
        return code;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    /** 稳定错误码;null = 旧调用点(前端按分类兜底)。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 可操作建议;null = 无。 */
    public String getHint() {
        return hint;
    }

    /** 单条异常的重试语义;null = 用分类默认值。 */
    public Boolean getRetryable() {
        return retryable;
    }

    /** 生效的重试语义(单条覆盖 > 分类默认)。 */
    public boolean isRetryable() {
        return retryable != null ? retryable : category.retryable();
    }
}
