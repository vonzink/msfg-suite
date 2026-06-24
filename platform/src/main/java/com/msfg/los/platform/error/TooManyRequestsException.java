package com.msfg.los.platform.error;

import org.springframework.http.HttpStatus;

/**
 * HTTP 429 — the caller exceeded a rate limit (security spec §6.3). Used by the staff-initiated
 * borrower-verification send throttle (≤3 sends / 15 min per {@code (org_id, borrower_id)} and per
 * acting staff {@code sub}). Extends {@link DomainException} so it carries status+code and renders the
 * standard {@code ApiError} envelope; {@code GlobalExceptionHandler} also has an explicit handler for
 * dedicated logging.
 */
public class TooManyRequestsException extends DomainException {
    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
    }
}
