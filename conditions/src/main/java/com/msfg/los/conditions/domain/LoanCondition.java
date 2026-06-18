package com.msfg.los.conditions.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A loan-scoped underwriting condition (e.g. "Provide most recent 2 paystubs"). Tenant-scoped via
 * {@link TenantScopedEntity} ({@code org_id} stamped + filtered by Hibernate {@code @TenantId}).
 * Soft-deleted via {@link #deletedAt} so a regulated LOS never hard-drops condition history.
 */
@Entity
@Table(name = "loan_condition")
@Getter
@Setter
public class LoanCondition extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Column(nullable = false, length = 2000)
    private String conditionText;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConditionStatus status = ConditionStatus.Outstanding;

    @Column(length = 120)
    private String assignedTo;

    private LocalDate dueDate;

    private Instant clearedAt;

    @Column(length = 120)
    private String clearedBy;

    @Column(length = 2000)
    private String notes;

    private Instant deletedAt;
}
