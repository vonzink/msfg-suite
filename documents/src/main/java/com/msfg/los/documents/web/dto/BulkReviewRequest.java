package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Bulk-review request: apply one {@code decision} to many documents in one call.
 *
 * <p>{@code decision} must be one of {@code ACCEPTED}, {@code REJECTED}, {@code NEEDS_BORROWER_ACTION}
 * (validated in the service). {@code notes} is required (non-blank) for {@code REJECTED} /
 * {@code NEEDS_BORROWER_ACTION}, optional for {@code ACCEPTED}. Per-document failures (e.g. a doc not
 * in a reviewable state) are COLLECTED, never aborting the batch.
 *
 * @param decision target review decision name (required)
 * @param docIds   documents to review (required, non-empty)
 * @param notes    reviewer notes (required for REJECTED / NEEDS_BORROWER_ACTION)
 */
public record BulkReviewRequest(
        @NotBlank String decision,
        @NotEmpty List<UUID> docIds,
        String notes) {
}
