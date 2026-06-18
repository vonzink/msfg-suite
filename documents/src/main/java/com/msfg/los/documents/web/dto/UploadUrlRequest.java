package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Step-1 request of the 3-step presigned upload flow: declares the file the client is about to
 * PUT. The server creates a {@code PENDING_UPLOAD} {@link com.msfg.los.documents.domain.Document}
 * row + a server-minted storage key, and returns a presigned upload URL (see
 * {@link UploadUrlResponse}). The bytes are not uploaded here — the client PUTs them to the
 * returned URL, then calls {@code confirm}.
 *
 * @param fileName       original client filename (required; sanitized into the storage key)
 * @param documentType   legacy {@link com.msfg.los.documents.domain.DocumentType} name (optional;
 *                       unknown/blank → {@code OTHER})
 * @param partyRole      organization-side party role (required; borrower/coborrower/lo/system)
 * @param contentType    declared MIME type (optional; validated vs the type's allowlist if both present)
 * @param folderId       explicit destination folder (optional; null → auto-route via the type's
 *                       default folder, else unfiled)
 * @param documentTypeId org-scoped {@link com.msfg.los.documents.domain.DocumentTypeCatalog} id
 *                       (optional; 404 if it does not resolve in the caller's org)
 */
public record UploadUrlRequest(
        @NotBlank String fileName,
        String documentType,
        @NotBlank String partyRole,
        String contentType,
        UUID folderId,
        UUID documentTypeId) {
}
