package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit row for a document status transition.
 * FK to {@code document(id)} cascades on document delete.
 */
@Entity
@Table(name = "document_status_history")
@Getter
@Setter
public class DocumentStatusHistory extends TenantScopedEntity {

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DocumentStatus status;

    @Column(name = "transitioned_at", nullable = false)
    private Instant transitionedAt;

    @Column(name = "transitioned_by", length = 120)
    private String transitionedBy;

    @Column(name = "note", length = 1000)
    private String note;
}
