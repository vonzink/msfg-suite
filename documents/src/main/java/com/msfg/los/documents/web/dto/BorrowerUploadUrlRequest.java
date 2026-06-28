package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Step-1 request of a BORROWER's presigned upload into the suite DMS. Unlike the staff
 * {@link UploadUrlRequest}, the borrower does NOT choose the {@code partyRole} (the service forces
 * {@code "borrower"}) — they only declare the file + optional type/folder. The bytes are PUT to the
 * returned URL, then the borrower calls {@code confirm}.
 *
 * @param fileName       original client filename (required; sanitized into the storage key)
 * @param contentType    declared MIME type (optional; validated vs the type's allowlist if both present)
 * @param documentTypeId org-scoped {@link com.msfg.los.documents.domain.DocumentTypeCatalog} id
 *                       (optional; 404 if it does not resolve in the caller's org)
 * @param folderId       explicit destination folder (optional; null → auto-route via the type's
 *                       default folder, else unfiled)
 */
public record BorrowerUploadUrlRequest(
        @NotBlank String fileName,
        String contentType,
        UUID documentTypeId,
        UUID folderId) {
}
