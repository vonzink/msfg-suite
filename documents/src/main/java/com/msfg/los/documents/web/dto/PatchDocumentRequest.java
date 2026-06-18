package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.DocumentType;

import java.util.UUID;

/**
 * Partial-update request for a document's metadata. Every field is optional — {@code null} means
 * "leave unchanged" (this is a PATCH, not a PUT). {@code folderId} (when non-null) must reference a
 * live folder in the same loan, else 400.
 *
 * <p>There is no field to clear {@code folderId} via this endpoint — unfiling/moving is done through
 * {@code POST /move} (which accepts a null target). Status/review fields are NOT settable here
 * (Task 6 owns the review actions).
 *
 * @param fileName     new display filename (null = leave)
 * @param folderId     new containing folder, must be live + same loan (null = leave)
 * @param documentType new legacy enum type (null = leave)
 * @param description  new free-text description (null = leave)
 */
public record PatchDocumentRequest(
        String fileName,
        UUID folderId,
        DocumentType documentType,
        String description) {
}
