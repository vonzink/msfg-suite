package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentStatus;
import com.msfg.los.documents.domain.DocumentType;

import java.time.Instant;
import java.util.UUID;

/**
 * Document view. Evolved for cutover Phase 1 to carry the full state surface (status, folder,
 * review fields, timestamps) the frontend needs.
 *
 * <p>Backward-compatibility: the legacy fields {@code sizeBytes} / {@code category} /
 * {@code generatedOn} / {@code requestedBy} are retained so the original multipart upload + list
 * contract (and its ITs) stay green. {@code fileSize} is the Phase-1 alias of {@code sizeBytes}.
 */
public record DocumentResponse(
        UUID id,
        DocumentType documentType,
        UUID documentTypeId,
        String fileName,
        Long fileSize,
        String contentType,
        String partyRole,
        DocumentStatus documentStatus,
        UUID folderId,
        String description,
        /** The sub of the principal who created (uploaded) this document — the audit createdBy. */
        String uploadedBy,
        String reviewedBy,
        String reviewerNotes,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        // ── retained legacy fields (keep the pre-cutover contract working) ──
        String category,
        Long sizeBytes,
        Instant generatedOn,
        String requestedBy
) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getDocumentType(),
                d.getDocumentTypeId(),
                d.getFileName(),
                d.getSizeBytes(),
                d.getContentType(),
                d.getPartyRole(),
                d.getDocumentStatus(),
                d.getFolderId(),
                d.getDescription(),
                d.getCreatedBy(),
                d.getReviewedBy(),
                d.getReviewerNotes(),
                d.getReviewedAt(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getCategory(),
                d.getSizeBytes(),
                d.getCreatedAt(),
                d.getCreatedBy()
        );
    }
}
