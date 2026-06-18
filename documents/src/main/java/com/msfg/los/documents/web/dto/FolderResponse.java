package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.Folder;

import java.time.Instant;
import java.util.UUID;

/**
 * A single node in a loan's folder tree. Unique simple name (springdoc keys schemas by simple
 * name; {@code OpenApiDocsIT} guards collisions). {@code evalPrompt} is resolved per-tree from
 * the org's folder-template map (Phase-4 AI column) — null for user folders / templateless folders.
 */
public record FolderResponse(
        UUID id,
        UUID parentId,
        String displayName,
        String sortKey,
        boolean isSystem,
        boolean isOldLoanArchive,
        boolean isDeleteFolder,
        UUID folderTemplateId,
        String evalPrompt,
        Instant createdAt,
        Instant updatedAt) {

    public static FolderResponse from(Folder f, String evalPrompt) {
        return new FolderResponse(
                f.getId(),
                f.getParentId(),
                f.getDisplayName(),
                f.getSortKey(),
                f.isSystem(),
                f.isOldLoanArchive(),
                f.isDeleteFolder(),
                f.getFolderTemplateId(),
                evalPrompt,
                f.getCreatedAt(),
                f.getUpdatedAt());
    }
}
