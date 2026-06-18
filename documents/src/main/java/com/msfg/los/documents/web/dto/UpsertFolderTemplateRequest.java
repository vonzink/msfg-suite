package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update for an org-scoped folder template. Unique simple name (springdoc keys
 * schemas by simple name; {@code OpenApiDocsIT} guards collisions). {@code displayName} is the
 * per-org unique key. {@code oldLoanArchive}/{@code deleteFolder} are app-enforced singletons
 * (≤1 active each per org). {@code evalPrompt} is a Phase-4 AI column (null clears).
 */
public record UpsertFolderTemplateRequest(
        @NotBlank @Size(max = 200) String displayName,
        @Size(max = 64) String sortKey,
        Boolean oldLoanArchive,
        Boolean deleteFolder,
        Boolean active,
        @PositiveOrZero Integer sortOrder,
        String evalPrompt) {

    public boolean oldLoanArchiveOrDefault() {
        return oldLoanArchive != null && oldLoanArchive;
    }

    public boolean deleteFolderOrDefault() {
        return deleteFolder != null && deleteFolder;
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public int sortOrderOrDefault() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
