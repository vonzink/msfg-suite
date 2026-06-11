package com.msfg.los.pricing.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Append-only lock-action audit entry (DB grants are SELECT/INSERT only). */
@Entity
@Table(name = "lock_event")
@Getter
@Setter
public class LockEvent extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private LockAction action;

    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    @Column(name = "commitment_days", nullable = false)
    private Integer commitmentDays;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;
}
