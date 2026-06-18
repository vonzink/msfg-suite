package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A loan-scoped folder in the document tree. One live root per loan (parentId null);
 * siblings are case-insensitively unique within a parent (DB partial unique indexes).
 * Soft-deleted via {@code deletedAt}; all reads filter it out.
 */
@Entity
@Table(name = "folder")
@Getter
@Setter
public class Folder extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /** {@code lower(trim(displayName))} — drives case-insensitive sibling uniqueness. */
    @Column(name = "name_normalized", nullable = false, length = 200)
    private String nameNormalized;

    @Column(name = "sort_key", length = 64)
    private String sortKey;

    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    @Column(name = "is_old_loan_archive", nullable = false)
    private boolean oldLoanArchive = false;

    @Column(name = "is_delete_folder", nullable = false)
    private boolean deleteFolder = false;

    @Column(name = "folder_template_id")
    private UUID folderTemplateId;

    // createdBy (who created the folder) is the inherited audit column from AuditableEntity.

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
