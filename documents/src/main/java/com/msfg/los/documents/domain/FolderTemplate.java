package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Org-scoped folder template — the default folder set materialized into a loan's tree.
 * App-enforced singletons (≤1 per org): {@code deleteFolder}, {@code oldLoanArchive}.
 * {@code evalPrompt} is a Phase-4 AI column (nullable).
 */
@Entity
@Table(name = "folder_template")
@Getter
@Setter
public class FolderTemplate extends TenantScopedEntity {

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "sort_key", length = 64)
    private String sortKey;

    @Column(name = "is_old_loan_archive", nullable = false)
    private boolean oldLoanArchive = false;

    @Column(name = "is_delete_folder", nullable = false)
    private boolean deleteFolder = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "eval_prompt", columnDefinition = "text")
    private String evalPrompt;
}
