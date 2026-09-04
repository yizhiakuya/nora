package com.nora.common.exception;

/**
 * Carries a business (non-HTTP) error code up to the REST layer,
 * where {@link GlobalExceptionHandler} turns it into {@code ApiResponse.error(code, message)}.
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
