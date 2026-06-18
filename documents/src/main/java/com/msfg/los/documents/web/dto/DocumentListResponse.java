package com.msfg.los.documents.web.dto;

import java.util.List;

/**
 * Flat list payload for {@code GET /loans/{loanId}/documents}: confirmed (UPLOADED+), non-deleted
 * documents, optionally folder-filtered. Not paged — it returns the loan's working set.
 */
public record DocumentListResponse(int count, List<DocumentResponse> documents) {
    public static DocumentListResponse of(List<DocumentResponse> docs) {
        return new DocumentListResponse(docs.size(), docs);
    }
}
