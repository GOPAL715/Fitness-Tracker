package com.fittrack.api;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central error contract for the API.
 *
 * <p>Every error response is structured JSON carrying a stable machine-readable {@code code}, a
 * safe human-readable {@code message} and the request correlation id. Stack traces, SQL text,
 * JDBC URLs, filesystem paths, JWT contents, provider credentials and internal exception class
 * names are never returned to a client; they stay in server-side logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Correlation id for the in-flight request, or null outside a request scope. */
    private static String correlation() {
        String value = MDC.get("request_id");
        return value == null ? null : value;
    }

    /** Builds the stable error envelope, adding the correlation id when one is available. */
    static Map<String, Object> body(int status, String error, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("code", code);
        body.put("message", message);
        String id = correlation();
        if (id != null) body.put("request_id", id);
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, "Bad Request", "invalid_request", e.getMessage()));
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    ResponseEntity<?> missing(java.util.NoSuchElementException e) {
        return ResponseEntity.status(404).body(body(404, "Not Found", "not_found", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(404).body(body(404, "Not Found", "not_found", "Resource endpoint not found"));
    }

    /** An unmapped route is a client error, not an internal failure. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(404).body(body(404, "Not Found", "not_found", "Resource endpoint not found"));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return ResponseEntity.badRequest().body(body(400, "Bad Request", "validation_failed", message));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body(400, "Bad Request", "malformed_body", "Invalid request body"));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> denied(org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(403).body(body(403, "Forbidden", "forbidden",
                e.getMessage() == null ? "Access denied" : e.getMessage()));
    }

    /**
     * An unparseable path variable or query parameter is a client error, so it must surface as a
     * structured 400 rather than an opaque 500.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(body(400, "Bad Request", "invalid_parameter",
                e.getName() + " has an invalid value"));
    }

    /** A malformed path UUID is a client error and must not reveal whether the id exists. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException.class)
    ResponseEntity<?> conversion(
            org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException e) {
        return ResponseEntity.badRequest().body(body(400, "Bad Request", "invalid_parameter",
                "A parameter has an unsupported type"));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String message = e.getReason() == null ? "Request failed" : e.getReason();
        String error = code == 409 ? "Conflict" : code == 404 ? "Not Found" : code == 401 ? "Unauthorized"
                : code == 403 ? "Forbidden" : code == 429 ? "Too Many Requests" : "Bad Request";
        String stable = code == 429 ? "rate_limited" : code == 409 ? "conflict" : "request_failed";
        return ResponseEntity.status(code).body(body(code, error, stable, message));
    }

    @ExceptionHandler(com.fittrack.ai.AiUsageService.QuotaExceededException.class)
    ResponseEntity<?> quota(com.fittrack.ai.AiUsageService.QuotaExceededException e) {
        return ResponseEntity.status(429)
                .body(body(429, "Too Many Requests", "ai_quota_exceeded", "AI request limit exceeded"));
    }

    @ExceptionHandler(com.fittrack.ai.AiProviderException.class)
    ResponseEntity<?> provider(com.fittrack.ai.AiProviderException e) {
        return ResponseEntity.status(502)
                .body(body(502, "Bad Gateway", "ai_provider_unavailable", "AI provider unavailable"));
    }

    @ExceptionHandler(com.fittrack.health.HealthProviderException.class)
    ResponseEntity<?> healthProvider(com.fittrack.health.HealthProviderException e) {
        String category = e.category();
        int code = "auth".equals(category) ? 401 : 502;
        return ResponseEntity.status(code).body(body(code, code == 401 ? "Unauthorized" : "Bad Gateway",
                "health_provider_" + category, "Health provider " + category));
    }

    /**
     * Unexpected failures are recorded server-side but returned as an opaque 500.
     *
     * <p>The exception type goes to the MDC for structured logs and never reaches the client.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        if (correlation() != null) MDC.put("error_type", e.getClass().getName());
        return ResponseEntity.internalServerError()
                .body(body(500, "Internal Server Error", "internal_error", "Unexpected error"));
    }
}
