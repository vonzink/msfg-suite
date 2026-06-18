package com.msfg.los.documents.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paged faceted-search payload for {@code GET /loans/{loanId}/documents/search}. Filtering happens
 * IN THE QUERY (JPA {@code Specification}); this is just the projection of a {@link Page}.
 */
public record DocumentSearchResponse(
        long totalElements,
        int totalPages,
        int page,
        int size,
        List<DocumentResponse> documents) {

    public static DocumentSearchResponse from(Page<?> page, List<DocumentResponse> docs) {
        return new DocumentSearchResponse(
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                docs);
    }
}
