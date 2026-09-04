package com.nora.common.response;

/**
 * Unified REST response envelope for all Nora services.
 *
 * <pre>{@code
 * {
 *   "code": 0,
 *   "data": ...,
 *   "message": "ok"
 * }
 * }</pre>
 *
 * @param code    business status code; 0 for success, non-zero for errors
 * @param data    response payload, null on error
 * @param message human-readable message
 */
public record ApiResponse<T>(int code, T data, String message) {

    /** Code used for every successful response. */
    public static final int SUCCESS_CODE = 0;
    /** Message used for every successful response. */
    public static final String SUCCESS_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, data, SUCCESS_MESSAGE);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}
