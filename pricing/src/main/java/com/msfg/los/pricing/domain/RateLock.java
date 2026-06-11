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

/** Current rate lock (1:1 per loan). Only LOCKED-state rows persist; expiry is computed. */
@Entity
@Table(name = "rate_lock")
@Getter
@Setter
public class RateLock extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "locked_rate", nullable = false)
    private BigDecimal lockedRate;

    @Column(name = "commitment_days", nullable = false)
    private Integer commitmentDays;

    @Column(name = "lock_date", nullable = false)
    private Instant lockDate;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Column(name = "extension_days_total", nullable = false)
    private int extensionDaysTotal = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_payer_type", nullable = false, length = 20)
    private CompensationPayerType compensationPayerType;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "interviewer_email", length = 320)
    private String interviewerEmail;
}
