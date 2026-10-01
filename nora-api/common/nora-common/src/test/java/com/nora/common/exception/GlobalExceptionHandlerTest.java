package com.nora.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class GlobalExceptionHandlerTest {
    @Test
    void wrapsFrameworkAndUnexpectedErrors() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        for (HttpStatus status : new HttpStatus[]{HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND,
                HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.UNSUPPORTED_MEDIA_TYPE}) {
            handler.handleExceptionInternal(new Exception("invalid input"), null,
                    new HttpHeaders(), status, request);
        }
        handler.handleUnexpected(new Exception("internal diagnostic"));
    }
}
