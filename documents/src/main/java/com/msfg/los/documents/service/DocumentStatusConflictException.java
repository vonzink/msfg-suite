package com.msfg.los.documents.service;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a document status transition is not permitted from the current state (HTTP 409).
 *
 * <p>Mirrors the codebase's "can't do that in this state" pattern — a dedicated {@link DomainException}
 * subtype carrying 409 + a stable code, exactly like pricing's {@code LockStateConflictException} and
 * the CoC PENDING-only guard. Distinguishes a <em>state conflict</em> (409) from <em>bad input</em>
 * ({@code ValidationException} → 400), which is the split the rest of the system uses.
 */
public class DocumentStatusConflictException extends DomainException {
    public DocumentStatusConflictException(String message) {
        super(HttpStatus.CONFLICT, "DOCUMENT_STATUS_CONFLICT", message);
    }
}
