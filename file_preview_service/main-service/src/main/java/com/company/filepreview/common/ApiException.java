package com.company.filepreview.common;

import org.springframework.http.HttpStatus;

/**
 * Application exception carrying a stable error code and HTTP status. Error codes
 * are mirrored from the Agent where relevant so the frontend can react uniformly.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException agentUnavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AGENT_UNAVAILABLE", message);
    }

    public static ApiException agentTimeout(String message) {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AGENT_TIMEOUT", message);
    }
}
