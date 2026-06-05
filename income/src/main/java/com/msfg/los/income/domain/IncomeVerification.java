package com.msfg.los.income.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "income_verification")
@Getter
@Setter
public class IncomeVerification extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationType verificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    private String provider;
    private String referenceNumber;
    private Instant orderedAt;
    private Instant completedAt;
}
