package com.msfg.los.platform.web;

import com.msfg.los.platform.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return ResponseEntity.status(ex.status())
            .body(ApiError.of(ex.code(), ex.getMessage(), Map.of(), Instant.now()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_ERROR", "Validation failed", fields, Instant.now()));
    }

    // Catch-all so unexpected failures return our ApiError envelope (not Spring's default /error shape).
    // Note: also catches some Spring MVC framework exceptions (e.g. 405) as 500 — acceptable for Spec 1;
    // revisit by extending ResponseEntityExceptionHandler when error handling is hardened.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(ApiError.of("INTERNAL_ERROR", "Unexpected error", Map.of(), Instant.now()));
    }
}
