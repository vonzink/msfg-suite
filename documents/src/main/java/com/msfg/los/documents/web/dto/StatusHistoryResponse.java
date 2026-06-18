package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.DocumentStatus;
import com.msfg.los.documents.domain.DocumentStatusHistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ordered status history for a document ({@code GET /{docId}/status-history}), oldest first.
 *
 * @param docId   the document
 * @param history append-only transition rows in chronological order
 */
public record StatusHistoryResponse(
        UUID docId,
        List<Entry> history) {

    /** One status transition audit row. */
    public record Entry(
            DocumentStatus status,
            Instant transitionedAt,
            String transitionedBy,
            String note) {
    }

    public static StatusHistoryResponse of(UUID docId, List<DocumentStatusHistory> rows) {
        List<Entry> entries = rows.stream()
                .map(h -> new Entry(h.getStatus(), h.getTransitionedAt(), h.getTransitionedBy(), h.getNote()))
                .toList();
        return new StatusHistoryResponse(docId, entries);
    }
}
