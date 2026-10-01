package com.nora.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.nora.common.response.ApiResponse;

/**
 * Catch-all REST exception handling for all Nora services (异常处理系统 2026-09-12).
 *
 * <p>职责:
 * <ul>
 *   <li>把异常映射为统一信封(含分类/错误码/hint/retryable/traceId)</li>
 *   <li>按分类定日志级别——用户错误(VALIDATION/NOT_FOUND)不刷 ERROR 堆栈,
 *       只有 INTERNAL 落完整堆栈;DEPENDENCY/UNAVAILABLE 等可重试错误落 WARN 一行</li>
 *   <li>HTTP 状态码与分类一致(前端可按状态或 category 分支)</li>
 * </ul>
 *
 * <p>每个依赖 nora-common 且扫描 {@code com.nora} 的服务自动继承。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 意外错误的兜底状态码。 */
    public static final int INTERNAL_ERROR_CODE = 500;

    /** 业务异常:信封携带完整分类信息;日志级别按分类。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        logByCategory(ex);
        return ResponseEntity.status(ex.getCategory().status()).body(ApiResponse.error(ex));
    }

    /** 分类驱动的日志级别:用户输入类降噪,依赖类一行 WARN,内部类完整堆栈。 */
    private void logByCategory(BusinessException ex) {
        switch (ex.getCategory()) {
            case VALIDATION, NOT_FOUND ->
                    log.debug("{} ({}): {}", ex.getCategory(), ex.getCode(), ex.getMessage());
            case UNAUTHORIZED, FORBIDDEN, CONFLICT ->
                    log.warn("{}: {}", ex.getCategory(), ex.getMessage());
            case RATE_LIMITED, DEPENDENCY, UNAVAILABLE, TIMEOUT ->
                    log.warn("{}: {} (retryable={})", ex.getCategory(), ex.getMessage(), ex.isRetryable());
            case INTERNAL -> log.error("INTERNAL: {}", ex.getMessage(), ex);
        }
    }

    /** 参数校验类失败(路径变量越界、非法取值等)按 400 语义返回,不落 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, ex.getMessage()));
    }

    /**
     * 非法状态(配置缺失/时序错误等):通常是环境/配置问题,归为 UNAVAILABLE
     * (可重试)而不是 500 bug——重启服务/补配置后可恢复。响应体仍带分类。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Service state error: {}", ex.getMessage());
        BusinessException mapped = new BusinessException(ErrorCategory.UNAVAILABLE,
                null, ex.getMessage(), "请检查服务配置或稍后重试", null, ex);
        return ResponseEntity.status(mapped.getCategory().status()).body(ApiResponse.error(mapped));
    }

    /** Spring MVC 自身的 400/404/405/415 等保留状态和响应头,统一包装信封。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.debug("HTTP {}: {}", status.value(), ex.getClass().getSimpleName());
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String message = knownStatus == null ? "Request failed" : knownStatus.getReasonPhrase();
        return new ResponseEntity<>(ApiResponse.error(status.value(), message), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        BusinessException mapped = new BusinessException(ErrorCategory.INTERNAL,
                null, "Internal server error", "请把 traceId 提供给管理员排障", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(mapped));
    }
}
