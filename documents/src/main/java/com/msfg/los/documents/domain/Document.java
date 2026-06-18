package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document")
@Getter
@Setter
public class Document extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    /** Legacy fixed enum (kept for generated docs / existing rows). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    private String category;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    @Column(nullable = false)
    private String storageKey;

    // ── Phase 1 (cutover) state fields ──────────────────────────────────────────────

    /** Containing folder (null = unfiled/root). */
    @Column(name = "folder_id")
    private UUID folderId;

    /** Org-scoped {@link DocumentTypeCatalog} entry (null ok). */
    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_status", nullable = false, length = 30)
    private DocumentStatus documentStatus = DocumentStatus.PENDING_UPLOAD;

    /** Organization-side party role only (borrower/coborrower/lo/system). */
    @Column(name = "party_role", length = 20)
    private String partyRole;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "reviewer_notes", length = 2000)
    private String reviewerNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** sha256 hex of the uploaded object (best-effort). */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "description", length = 1000)
    private String description;

    /** Soft-delete marker; all reads filter {@code deletedAt is null}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
