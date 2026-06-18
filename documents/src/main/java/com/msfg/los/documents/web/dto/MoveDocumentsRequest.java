package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Bulk-move request: relocate the given documents into {@code toFolderId}. A null {@code toFolderId}
 * unfiles them (folderId → null). The target (when non-null) must be a live folder in the same loan,
 * else 400. Documents not found / not in the loan are silently skipped — the response reports how
 * many of the requested ids actually moved.
 *
 * @param docIds     ids to move (required, non-empty)
 * @param toFolderId destination folder, or null to unfile
 */
public record MoveDocumentsRequest(
        @NotEmpty List<UUID> docIds,
        UUID toFolderId) {
}
