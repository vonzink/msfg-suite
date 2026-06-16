package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update for an org-scoped document type. Unique simple name (springdoc keys schemas
 * by simple name; {@code OpenApiDocsIT} guards collisions). {@code slug} is lower-kebab, the
 * per-org unique business key. {@code active}/{@code sortOrder} default sensibly when omitted.
 */
public record UpsertDocumentTypeRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "[a-z0-9-]+", message = "slug must be lower-kebab (a-z, 0-9, '-')")
        String slug,
        @Size(max = 200) String defaultFolderName,
        @Size(max = 500) String requiredForMilestones,
        @Size(max = 500) String allowedMimeTypes,
        @PositiveOrZero Long maxFileSizeBytes,
        Boolean active,
        @PositiveOrZero Integer sortOrder) {

    /** Defaulted active flag (true when omitted). */
    public boolean activeOrDefault() {
        return active == null || active;
    }

    /** Defaulted sort order (0 when omitted). */
    public int sortOrderOrDefault() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
