package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.DocumentTypeCatalog;

import java.util.UUID;

/**
 * Org-scoped document-type catalog view. Unique simple name (springdoc keys schemas by simple
 * name; {@code OpenApiDocsIT} guards collisions). Staff-only surface — no borrower-visibility
 * field (the cutover document subsystem is staff-only).
 */
public record DocumentTypeResponse(
        UUID id,
        String name,
        String slug,
        String defaultFolderName,
        String allowedMimeTypes,
        Long maxFileSizeBytes,
        boolean isActive,
        int sortOrder) {

    public static DocumentTypeResponse from(DocumentTypeCatalog c) {
        return new DocumentTypeResponse(
                c.getId(),
                c.getName(),
                c.getSlug(),
                c.getDefaultFolderName(),
                c.getAllowedMimeTypes(),
                c.getMaxFileSizeBytes(),
                c.isActive(),
                c.getSortOrder());
    }
}
