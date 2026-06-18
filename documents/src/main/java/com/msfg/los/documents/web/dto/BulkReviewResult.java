package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.DocumentStatus;

import java.util.List;
import java.util.UUID;

/**
 * Result of {@code POST /bulk-review}: counts + the per-document failures that did NOT abort the batch.
 *
 * @param requested number of doc ids submitted
 * @param succeeded number that transitioned successfully
 * @param failed    number that failed (== {@code failures.size()})
 * @param decision  the applied review decision
 * @param failures  per-doc failure reasons (collected, batch never aborted)
 */
public record BulkReviewResult(
        int requested,
        int succeeded,
        int failed,
        DocumentStatus decision,
        List<Failure> failures) {

    /** A single doc that could not be reviewed, with the human-readable reason. */
    public record Failure(UUID docId, String error) {
    }
}
