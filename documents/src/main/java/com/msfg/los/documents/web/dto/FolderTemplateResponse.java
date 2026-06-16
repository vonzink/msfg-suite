package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.FolderTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Org-scoped folder-template view. Unique simple name (springdoc keys schemas by simple name;
 * {@code OpenApiDocsIT} guards collisions). {@code evalPrompt} is a Phase-4 AI column (nullable).
 */
public record FolderTemplateResponse(
        UUID id,
        String displayName,
        String sortKey,
        boolean isOldLoanArchive,
        boolean isDeleteFolder,
        boolean isActive,
        int sortOrder,
        String evalPrompt,
        Instant createdAt,
        Instant updatedAt) {

    public static FolderTemplateResponse from(FolderTemplate t) {
        return new FolderTemplateResponse(
                t.getId(),
                t.getDisplayName(),
                t.getSortKey(),
                t.isOldLoanArchive(),
                t.isDeleteFolder(),
                t.isActive(),
                t.getSortOrder(),
                t.getEvalPrompt(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
