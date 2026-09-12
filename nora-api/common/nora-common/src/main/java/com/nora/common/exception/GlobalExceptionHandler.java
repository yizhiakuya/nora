package com.nora.common.exception;

import com.nora.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Fallback code for unexpected errors. */
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

    /**
     * 静态资源未命中(prometheus/actuator 抓取 404 等):高频且无排障价值,
     * 降为 DEBUG 单行,不再刷 ERROR 堆栈。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        if (log.isDebugEnabled()) {
            log.debug("No static resource: {}", ex.getResourcePath());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "Not found: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        BusinessException mapped = new BusinessException(ErrorCategory.INTERNAL,
                null, "Internal server error: " + ex.getMessage(), "请把 traceId 提供给管理员排障", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(mapped));
    }
}
