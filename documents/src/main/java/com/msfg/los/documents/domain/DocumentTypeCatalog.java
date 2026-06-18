package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Org-scoped document-type catalog entry (table {@code document_type}).
 *
 * NOTE: deliberately NOT named {@code DocumentType} — that simple name is already taken by the
 * legacy {@link DocumentType} enum, and springdoc keys schemas by SIMPLE class name
 * (a collision → /v3/api-docs 500; OpenApiDocsIT guards this).
 */
@Entity
@Table(name = "document_type")
@Getter
@Setter
public class DocumentTypeCatalog extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "default_folder_name", length = 200)
    private String defaultFolderName;

    @Column(name = "required_for_milestones", length = 500)
    private String requiredForMilestones;

    /** CSV of allowed MIME types (e.g. {@code application/pdf,image/jpeg}). */
    @Column(name = "allowed_mime_types", length = 500)
    private String allowedMimeTypes;

    @Column(name = "max_file_size_bytes")
    private Long maxFileSizeBytes;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
