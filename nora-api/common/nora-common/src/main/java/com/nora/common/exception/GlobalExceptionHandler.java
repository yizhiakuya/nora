package com.nora.common.exception;

import com.nora.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Catch-all REST exception handling for all Nora services.
 * Every service that depends on nora-common and enables component scanning
 * of {@code com.nora} inherits these handlers automatically.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Fallback code for unexpected errors. */
    public static final int INTERNAL_ERROR_CODE = 500;

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        log.warn("Business error {}: {}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 静态资源未命中(prometheus/actuator 抓取 404 等):高频且无排障价值,
     * 降为 DEBUG 单行,不再刷 ERROR 堆栈。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        if (log.isDebugEnabled()) {
            log.debug("No static resource: {}", ex.getResourcePath());
        }
        return ApiResponse.error(404, "Not found: " + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ApiResponse.error(INTERNAL_ERROR_CODE, "Internal server error: " + ex.getMessage());
    }
}
