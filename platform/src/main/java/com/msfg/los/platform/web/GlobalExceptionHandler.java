package com.msfg.los.platform.web;

import com.msfg.los.platform.error.DomainException;
import com.msfg.los.platform.error.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    // Rate-limit breach (e.g. OTP send throttle, security spec §6.3) → 429. Explicit handler
    // alongside the DIV→409 / optimistic-lock→409 ones for dedicated logging (Spring still selects
    // the most-specific handler, so this wins over handleDomain for TooManyRequestsException).
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ApiError.of(ex.code(), ex.getMessage(), Map.of(), Instant.now()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_ERROR", "Validation failed", fields, Instant.now()));
    }

    // DB constraint violations (unique/check) reach here only on races that beat an app-level
    // pre-check (e.g. concurrent duplicate-key inserts) — the conflict answer is 409, not 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("CONFLICT", "Conflicting resource state (duplicate or constraint violation)",
                Map.of(), Instant.now()));
    }

    // @Version conflicts (e.g. two concurrent updates to the same rate_lock row) surface as
    // OptimisticLockingFailureException — same race class as above: the conflict answer is 409, not 500.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("CONFLICT", "Concurrent modification — retry",
                Map.of(), Instant.now()));
    }

    // Malformed JSON / invalid enum constant in a request body is a client error (400), not a 500.
    // Generic message on purpose: parser details can echo request-body fragments (NPI risk).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_ERROR", "Malformed request body", Map.of(), Instant.now()));
    }

    // Path-variable / query-param conversion failures (unknown enum constant, non-UUID id) are
    // client errors (400), not 500s. Echo the parameter NAME only — never the offending value
    // (request fragments can carry NPI).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_ERROR", "Invalid value for parameter '" + ex.getName() + "'",
                Map.of(), Instant.now()));
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
