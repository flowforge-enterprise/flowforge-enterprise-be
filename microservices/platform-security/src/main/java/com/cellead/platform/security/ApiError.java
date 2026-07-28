package com.cellead.platform.security;

import java.time.Instant;

public record ApiError(String error, String message, Instant timestamp, String correlationId) {
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now(), org.slf4j.MDC.get("correlationId"));
    }
}
