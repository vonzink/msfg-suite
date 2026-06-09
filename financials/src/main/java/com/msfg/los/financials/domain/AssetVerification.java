package com.msfg.los.financials.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_verification")
@Getter
@Setter
public class AssetVerification extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetVerificationType verificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetVerificationStatus status;

    private String provider;
    private String referenceNumber;
    private Instant orderedAt;
    private Instant completedAt;
}
