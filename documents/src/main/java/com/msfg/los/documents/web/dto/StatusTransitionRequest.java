package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Generic status transition (the {@code PUT /{docId}/status} endpoint). {@code status} is the target
 * {@code DocumentStatus} name; the transition is validated against the pure transition table (illegal
 * edges → 409). {@code note} is an optional free-text audit note appended to status history.
 *
 * @param status target status name (required); unknown constant → 400
 * @param note   optional audit note (≤1000 chars persisted)
 */
public record StatusTransitionRequest(
        @NotBlank String status,
        String note) {
}
