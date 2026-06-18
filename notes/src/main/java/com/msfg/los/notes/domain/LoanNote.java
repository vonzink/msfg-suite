package com.msfg.los.notes.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A loan-scoped free-text note (e.g. a processor's running commentary on a file). Tenant-scoped via
 * {@link TenantScopedEntity} ({@code org_id} stamped + filtered by Hibernate {@code @TenantId}).
 *
 * <p>The author is stamped at create time from the authenticated principal ({@link #authorId} =
 * the principal id/sub, {@link #authorName} = display name) so the note carries who-wrote-it even if
 * the user record later changes. Notes are HARD-deleted (mortgage-app parity) — they are operational
 * commentary, not regulated condition/loan history.
 */
@Entity
@Table(name = "loan_note")
@Getter
@Setter
public class LoanNote extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Column(length = 120)
    private String authorId;

    @Column(length = 200)
    private String authorName;

    @Column(nullable = false, length = 2000)
    private String content;
}
