package com.nora.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nora.common.exception.BusinessException;
import com.nora.common.exception.ErrorCategory;
import com.nora.common.logging.TraceContext;

/**
 * 全部 Nora 服务的统一 REST 响应信封。
 *
 * <p>成功路径保持三段固定形状 {@code {code, data, message}}(data 即使为 null
 * 也不省略——前端 isEnvelope 依赖 data 键存在);错误路径追加结构化字段
 * {@code category/errorCode/hint/retryable/traceId}(null 时省略)。
 *
 * @param code      业务状态码;0 成功,非 0 错误
 * @param data      响应载荷,错误时为 null
 * @param message   人类可读消息
 * @param category  错误分类(前端选文案/操作);成功时为 null
 * @param errorCode 稳定错误码(前端精确分支);可为 null
 * @param hint      可操作建议;可为 null
 * @param retryable 是否值得重试;可为 null(按分类默认)
 * @param traceId   链路 ID(报障定位);成功时为 null
 */
public record ApiResponse<T>(int code, T data, String message,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String category,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String hint,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Boolean retryable,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {

    /** 每个成功响应使用的状态码。 */
    public static final int SUCCESS_CODE = 0;
    /** 每个成功响应使用的消息。 */
    public static final String SUCCESS_MESSAGE = "ok";

    /** 兼容构造器(3 参):成功/旧式错误。 */
    public ApiResponse(int code, T data, String message) {
        this(code, data, message, null, null, null, null, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, data, SUCCESS_MESSAGE);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /** 旧式错误(无分类);保留供兼容,新代码走 {@link #error(BusinessException)}。 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, null, message, ErrorCategory.fromHttpCode(code).name(),
                null, null, null, TraceContext.traceId());
    }

    /** 结构化错误:从 BusinessException 提取分类/错误码/hint/retryable + 当前 traceId。 */
    public static <T> ApiResponse<T> error(BusinessException ex) {
        return new ApiResponse<>(ex.getCode(), null, ex.getMessage(),
                ex.getCategory().name(), ex.getErrorCode(), ex.getHint(),
                ex.isRetryable(), TraceContext.traceId());
    }
}
